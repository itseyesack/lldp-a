package com.net.lldpsniffer.usb

import android.content.Context
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import com.net.lldpsniffer.model.CapturedPacket
import com.net.lldpsniffer.parser.PacketParser
import com.net.lldpsniffer.usb.driver.AsixAx88179Driver
import com.net.lldpsniffer.usb.driver.AsixAx88772Driver
import com.net.lldpsniffer.usb.driver.RealtekRtl8153Driver
import com.net.lldpsniffer.usb.driver.VendorAdapterDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class UsbConnectionState {
    object Disconnected : UsbConnectionState()
    data class DeviceDetected(val deviceName: String, val vendorId: Int, val productId: Int) : UsbConnectionState()
    object PermissionRequested : UsbConnectionState()
    object PermissionDenied : UsbConnectionState()
    data class Connecting(val stepDescription: String) : UsbConnectionState()
    data class Connected(val deviceName: String, val configValue: Int, val bulkInEndpoint: Int) : UsbConnectionState()
    data class Error(val message: String, val isKernelContention: Boolean = false) : UsbConnectionState()
}

class UsbConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbConnManager"

        // CDC ECM Control Transfer Constants
        private const val REQUEST_TYPE_CDC_CONTROL = 0x21
        private const val SET_ETHERNET_PACKET_FILTER = 0x43
        // Packet filter bitmask: Promiscuous (0x01) | All Multicast (0x02) | Directed (0x04) | Broadcast (0x08) | Multicast (0x10)
        private const val PACKET_FILTER_MASK = 0x1F

        // Standard USB GET_CONFIGURATION request (USB 2.0 spec 9.4.2)
        private const val REQUEST_TYPE_STANDARD_DEVICE_IN = 0x80
        private const val REQUEST_GET_CONFIGURATION = 0x08
    }

    // Chip-specific vendor-mode bring-up/link-poll drivers. Adding support for a new USB
    // Ethernet chipset means writing one new VendorAdapterDriver implementation and
    // registering it here - runCaptureSetup/runIngestionLoop never reference a specific
    // chip's registers directly.
    private val vendorDrivers: List<VendorAdapterDriver> = listOf(
        RealtekRtl8153Driver(),
        AsixAx88179Driver(),
        AsixAx88772Driver()
    )
    private var activeVendorDriver: VendorAdapterDriver? = null

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val _capturedPackets = MutableSharedFlow<CapturedPacket>(extraBufferCapacity = 100)
    val capturedPackets: SharedFlow<CapturedPacket> = _capturedPackets.asSharedFlow()

    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()

    // null = unknown/not yet read, true/false = last confirmed PLA_PHYSTATUS LINK_STATUS bit.
    // Refreshed continuously by runIngestionLoop, not just once at setup, since PHY link
    // (re)negotiation can complete seconds after the cable is plugged in.
    private val _linkState = MutableStateFlow<Boolean?>(null)
    val linkState: StateFlow<Boolean?> = _linkState.asStateFlow()

    fun logDiag(msg: String) {
        Log.d(TAG, msg)
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val formattedMsg = "[$timeStr] $msg"
        _diagnosticLogs.update { current ->
            (listOf(formattedMsg) + current).take(500)
        }
        try {
            val logFile = File(context.cacheDir, "l2_hardware_logs.txt")
            logFile.appendText("$formattedMsg\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing log file", e)
        }
    }

    fun exportLogsToFile(): File? {
        return try {
            val logFile = File(context.cacheDir, "l2_hardware_logs.txt")
            if (!logFile.exists() || logFile.length() == 0L) {
                val text = _diagnosticLogs.value.reversed().joinToString("\n")
                logFile.writeText(text)
            }
            logFile
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting log file", e)
            null
        }
    }

    fun clearDiagLogs() {
        _diagnosticLogs.value = emptyList()
        try {
            val logFile = File(context.cacheDir, "l2_hardware_logs.txt")
            if (logFile.exists()) logFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing log file", e)
        }
    }

    /**
     * Reads the device's actual active bConfigurationValue via the standard
     * GET_CONFIGURATION request, instead of inferring it from setConfiguration()'s
     * boolean return value (which does not distinguish "already in that config"
     * from "denied because a kernel driver still holds an interface").
     */
    private fun getActiveConfigurationValue(connection: UsbDeviceConnection): Int? {
        val buf = ByteArray(1)
        val result = connection.controlTransfer(
            REQUEST_TYPE_STANDARD_DEVICE_IN, REQUEST_GET_CONFIGURATION, 0, 0, buf, 1, 1000
        )
        return if (result >= 1) (buf[0].toInt() and 0xFF) else null
    }

    /**
     * Logs which network interfaces the kernel currently sees (e.g. eth0/usb0/enx*).
     * If the RTL8153 already shows up here, the kernel's own r8152/cdc_ether driver has
     * bound it and is actively reconfiguring the same registers we are trying to own -
     * this is visible from within the app's own diagnostic log, so it can be checked
     * even though the adapter's USB-C port can't be shared with a debugger cable.
     */
    private fun logKernelNetworkState(label: String) {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val desc = if (ifaces.isEmpty()) "none" else ifaces.joinToString(", ") {
                "${it.name}(up=${try { it.isUp } catch (e: Exception) { "?" }})"
            }
            logDiag("Kernel network interfaces [$label]: $desc")
        } catch (e: Exception) {
            logDiag("Could not enumerate kernel network interfaces [$label]: ${e.message}")
        }
    }

    private var activeConnection: UsbDeviceConnection? = null
    private var activeDevice: UsbDevice? = null
    private var captureJob: Job? = null
    private var coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val claimedInterfaces = mutableListOf<UsbInterface>()

    /**
     * `UsbDevice.interfaceCount`/`getInterface(i)` enumerate interfaces across ALL of the
     * device's USB configurations concatenated together, not just the one currently active -
     * confirmed on real hardware where GET_CONFIGURATION reported 1 active interface while
     * device.interfaceCount reported 4, with duplicate ids across configs. Interfaces must be
     * looked up via the specific UsbConfiguration instead.
     */
    private fun interfacesForConfig(device: UsbDevice, configValue: Int?): List<UsbInterface> {
        for (i in 0 until device.configurationCount) {
            val config = device.getConfiguration(i)
            if (configValue == null || config.id == configValue) {
                return (0 until config.interfaceCount).map { config.getInterface(it) }
            }
        }
        return emptyList()
    }

    private fun claimAndTrack(connection: UsbDeviceConnection, iface: UsbInterface, label: String): Boolean {
        val claimed = try {
            connection.claimInterface(iface, true)
        } catch (e: Exception) {
            logDiag("Claim $label (id=${iface.id}) threw: ${e.message}")
            false
        }
        logDiag("Claimed $label (id=${iface.id}, class=${iface.interfaceClass}, alt=${iface.alternateSetting}): $claimed")
        if (claimed) claimedInterfaces.add(iface)
        return claimed
    }

    fun findSupportedDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        logDiag("Querying USB device list (${deviceList.size} attached USB devices)...")
        for ((name, device) in deviceList) {
            logDiag("USB Device: $name (VID: 0x${String.format("%04X", device.vendorId)}, PID: 0x${String.format("%04X", device.productId)}, Class: ${device.deviceClass})")
            if (isEthernetDevice(device)) {
                logDiag("Found Ethernet adapter candidate: ${device.deviceName}")
                return device
            }
        }
        logDiag("No supported USB Ethernet adapter found in device list.")
        return null
    }

    fun isEthernetDevice(device: UsbDevice): Boolean {
        if (vendorDrivers.any { it.matches(device) }) {
            return true
        }
        if (device.vendorId == RealtekRtl8153Driver.REALTEK_VID || device.vendorId == AsixAx88179Driver.ASIX_VID) {
            return true
        }
        // Check USB Classes
        if (device.deviceClass == UsbConstants.USB_CLASS_COMM || device.deviceClass == 255) {
            return true
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                iface.interfaceClass == 255) {
                return true
            }
        }
        return false
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun startCapture(device: UsbDevice) {
        synchronized(this) {
            if (activeDevice == device && captureJob?.isActive == true) {
                logDiag("Capture process already active for device ${device.deviceName}. Ignoring duplicate start.")
                return
            }
            activeDevice = device
            captureJob?.cancel()
            captureJob = coroutineScope.launch { runCaptureSetup(device) }
        }

        _connectionState.value = UsbConnectionState.Connecting("Opening USB Device...")
        logDiag("Starting capture for device ${device.deviceName} (VID: 0x${String.format("%04X", device.vendorId)}, PID: 0x${String.format("%04X", device.productId)})...")
    }

    private suspend fun runCaptureSetup(device: UsbDevice) {
        try {
            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("usbManager.openDevice returned null. USB Host permission might be missing.")

            activeConnection = connection
            activeVendorDriver = null
            claimedInterfaces.clear()
            logDiag("UsbDeviceConnection opened successfully.")

            // Diagnostic: if the kernel already owns this device (eth0/usb0/enx*), its
            // r8152/cdc_ether driver is actively reprogramming the same registers we're
            // about to touch, which is a likely cause of every write below returning -1.
            // This is visible in-app (DiagnosticLogsCard) without needing a debugger cable,
            // since the USB-C port is occupied by the adapter during capture.
            logKernelNetworkState("before claim")

            // Step 0: Determine the configuration the device is ACTUALLY in right now.
            // setConfiguration()'s boolean return does not distinguish "already there"
            // from "denied because a kernel driver still holds an interface" - so we ask
            // the device directly instead of guessing.
            val activeConfigBefore = getActiveConfigurationValue(connection)
            logDiag("GET_CONFIGURATION (before): ${activeConfigBefore?.let { "Config $it" } ?: "read failed"}")

            var cdcConfig: UsbConfiguration? = null
            for (i in 0 until device.configurationCount) {
                val config = device.getConfiguration(i)
                logDiag("Config $i: id=${config.id}, interfaces=${config.interfaceCount}")
                if (config.id == 2) cdcConfig = config
            }

            // Step 1: Force-claim (and KEEP claimed) whatever interfaces are presently
            // active, to detach any kernel driver bound to them. Releasing an interface
            // that was force-claimed causes the kernel to re-probe and re-bind the
            // original driver (usb_unbind_and_rebind_marked_interfaces), so unlike the
            // previous implementation we do not release these until stopCapture().
            _connectionState.value = UsbConnectionState.Connecting("Detaching kernel driver...")
            val preSwitchIfaces = interfacesForConfig(device, activeConfigBefore)
            logDiag("Active configuration exposes ${preSwitchIfaces.size} interface(s) (device.interfaceCount across all configs is ${device.interfaceCount}).")
            for (iface in preSwitchIfaces) {
                claimAndTrack(connection, iface, "pre-switch interface ${iface.id}")
            }

            var activeConfigValue = activeConfigBefore ?: 1
            if (cdcConfig != null && activeConfigValue != 2) {
                // usbfs's SET_CONFIGURATION rejects the switch while any interface of the
                // current configuration is claimed (the claim becomes stale anyway, since
                // switching configuration destroys and re-enumerates every interface object).
                // This device shows no kernel network interface before OR after claiming
                // (logKernelNetworkState consistently reports "none"), meaning there is no
                // kernel driver here to detach in the first place - so releasing before the
                // switch is safe on this hardware, unlike the force-claim-and-keep rationale
                // that applies once we're actually capturing.
                for (iface in preSwitchIfaces) {
                    try {
                        connection.releaseInterface(iface)
                    } catch (e: Exception) {
                        logDiag("Release pre-switch interface ${iface.id} threw: ${e.message}")
                    }
                }
                claimedInterfaces.clear()

                _connectionState.value = UsbConnectionState.Connecting("Setting Configuration 2 (CDC-ECM)...")
                val setResult = connection.setConfiguration(cdcConfig)
                val activeConfigAfter = getActiveConfigurationValue(connection)
                logDiag("setConfiguration(2) returned $setResult; GET_CONFIGURATION (after): ${activeConfigAfter?.let { "Config $it" } ?: "read failed"}")

                if (activeConfigAfter == 2) {
                    activeConfigValue = 2
                    // Interfaces are re-enumerated under the new configuration; our
                    // earlier claims against the old configuration's interface objects
                    // are stale, so claim again against the now-active interface set.
                    claimedInterfaces.clear()
                    for (iface in interfacesForConfig(device, activeConfigAfter)) {
                        claimAndTrack(connection, iface, "post-switch interface ${iface.id}")
                    }
                } else {
                    logDiag("Configuration switch to 2 did not take effect. Continuing with actually-active Config $activeConfigValue instead of assuming success.")
                    // The switch attempt released our vendor-mode claims above; re-claim
                    // whatever configuration is actually active now that we're falling back.
                    for (iface in interfacesForConfig(device, activeConfigValue)) {
                        claimAndTrack(connection, iface, "post-fallback interface ${iface.id}")
                    }
                }
            } else if (cdcConfig == null) {
                logDiag("Config 2 (CDC-ECM) not found in configuration list. Operating on Config $activeConfigValue.")
            }

            // Step 2: Find interfaces/endpoints for whichever configuration is confirmed active.
            _connectionState.value = UsbConnectionState.Connecting("Claiming Interfaces...")

            var controlIface: UsbInterface? = null
            var dataIface: UsbInterface? = null
            var bulkInEndpoint: UsbEndpoint? = null
            var vendorRxConfirmed = true

            if (activeConfigValue == 2 && cdcConfig != null) {
                logDiag("Operating in CDC-ECM Mode (Configuration 2)...")
                for (i in 0 until cdcConfig.interfaceCount) {
                    val iface = cdcConfig.getInterface(i)
                    logDiag("Config 2 Interface $i: id=${iface.id}, class=${iface.interfaceClass}, subclass=${iface.interfaceSubclass}, altSetting=${iface.alternateSetting}, epCount=${iface.endpointCount}")
                    if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                        controlIface = iface
                    } else if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                        if (iface.endpointCount > 0 || dataIface == null) {
                            dataIface = iface
                        }
                    }
                }

                controlIface?.let { claimAndTrack(connection, it, "CDC Control Interface") }

                dataIface?.let { iface ->
                    if (claimAndTrack(connection, iface, "CDC Data Interface")) {
                        val setIfRes = connection.setInterface(iface)
                        logDiag("connection.setInterface(id=${iface.id}, alt=${iface.alternateSetting}) result: $setIfRes")
                    }
                }

                // Step 3: Hardware Initialization - Send CDC Packet Filter Control Transfer
                _connectionState.value = UsbConnectionState.Connecting("Sending SET_ETHERNET_PACKET_FILTER (0x1F)...")
                val controlInterfaceId = controlIface?.id ?: 0
                val filterResult = connection.controlTransfer(
                    REQUEST_TYPE_CDC_CONTROL,
                    SET_ETHERNET_PACKET_FILTER,
                    PACKET_FILTER_MASK,
                    controlInterfaceId,
                    null,
                    0,
                    1000
                )
                logDiag("SET_ETHERNET_PACKET_FILTER controlTransfer returned: $filterResult (>= 0 indicates success)")
                vendorRxConfirmed = filterResult >= 0

                dataIface?.let { iface ->
                    for (i in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(i)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                            bulkInEndpoint = ep
                            break
                        }
                    }
                }
            } else {
                logDiag("Operating in Fallback / Vendor Mode (Configuration $activeConfigValue)...")
                val vendorIface = interfacesForConfig(device, activeConfigValue).firstOrNull()
                if (vendorIface != null) {
                    if (claimedInterfaces.none { it.id == vendorIface.id }) {
                        claimAndTrack(connection, vendorIface, "Vendor Interface")
                    }

                    // Dispatch to whichever driver's VID/PID matches this device, instead
                    // of assuming RTL8153 - a device with no matching driver still gets a
                    // best-effort attempt (its default post-enumeration RX state may already
                    // work), just without chip-specific register bring-up.
                    val matchedDriver = vendorDrivers.firstOrNull { it.matches(device) }
                    activeVendorDriver = matchedDriver
                    if (matchedDriver != null) {
                        logDiag("Matched vendor adapter driver: ${matchedDriver.name}")
                        vendorRxConfirmed = matchedDriver.bringUp(connection, ::logDiag)
                        _linkState.value = matchedDriver.pollLinkUp(connection, ::logDiag)
                    } else {
                        logDiag("No known vendor adapter driver for VID 0x${String.format("%04X", device.vendorId)} / PID 0x${String.format("%04X", device.productId)} - skipping chip-specific bring-up.")
                        vendorRxConfirmed = true
                    }

                    for (i in 0 until vendorIface.endpointCount) {
                        val ep = vendorIface.getEndpoint(i)
                        logDiag("Vendor Iface Endpoint $i: address=0x${String.format("%02X", ep.address)}, dir=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"}, type=${ep.type}")
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                            bulkInEndpoint = ep
                            break
                        }
                    }
                }
            }

            // Global fallback for Bulk IN endpoint if not found above
            if (bulkInEndpoint == null) {
                for (iface in interfacesForConfig(device, activeConfigValue)) {
                    for (j in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(j)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                            if (claimedInterfaces.none { it.id == iface.id }) {
                                claimAndTrack(connection, iface, "fallback interface ${iface.id}")
                            }
                            bulkInEndpoint = ep
                            break
                        }
                    }
                    if (bulkInEndpoint != null) break
                }
            }

            val epIn = bulkInEndpoint
                ?: throw IllegalStateException("Bulk IN endpoint not found on USB Ethernet adapter.")

            if (!vendorRxConfirmed) {
                throw IllegalStateException(
                    "Hardware RX configuration failed (register writes rejected). " +
                        "The interface is likely still owned by the kernel driver, or the wrong configuration is active."
                )
            }

            logDiag("Selected Endpoint: address=0x${String.format("%02X", epIn.address)}, dir=${if (epIn.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"}, type=${epIn.type}, maxPacketSize=${epIn.maxPacketSize}")
            logKernelNetworkState("after claim")

            _connectionState.value = UsbConnectionState.Connected(
                deviceName = device.deviceName ?: "USB Ethernet Adapter",
                configValue = activeConfigValue,
                bulkInEndpoint = epIn.address
            )

            // Step 5: Continuous Ingestion Pipeline
            // Link polling only happens when a vendor driver matched this device; CDC-ECM
            // (Config 2) has no equivalent poll wired up here, so live link tracking is
            // vendor-mode only.
            runIngestionLoop(connection, epIn, activeVendorDriver)

        } catch (e: Exception) {
            logDiag("Error during USB capture setup: ${e.localizedMessage}")
            _connectionState.value = UsbConnectionState.Error(
                message = e.localizedMessage ?: "USB capture setup failed."
            )
        }
    }

    private suspend fun runIngestionLoop(connection: UsbDeviceConnection, endpointIn: UsbEndpoint, driver: VendorAdapterDriver?) {
        val readBuffer = ByteArray(20480) // 20KB buffer - matches AX88179's recommended rx_urb_size
        logDiag("Starting Bulk IN packet ingestion loop on EP Address 0x${String.format("%02X", endpointIn.address)} (Buffer=20KB)...")

        val startTime = System.currentTimeMillis()
        var lastLogTime = System.currentTimeMillis()
        var lastLinkPollTime = System.currentTimeMillis()
        var totalBytesRead = 0L
        var consecutiveErrors = 0

        withContext(Dispatchers.IO) {
            while (isActive && activeConnection == connection) {
                val bytesRead = connection.bulkTransfer(endpointIn, readBuffer, readBuffer.size, 1000)
                val now = System.currentTimeMillis()

                // Keep the link indicator live for the whole capture, not just at setup -
                // a cable can be plugged/unplugged or (re)negotiate mid-capture. Only log
                // when the state actually changes, to avoid spamming the log every 2s.
                if (driver != null && now - lastLinkPollTime >= 2000) {
                    lastLinkPollTime = now
                    val newLinkUp = driver.pollLinkUp(connection, ::logDiag)
                    if (newLinkUp != _linkState.value) {
                        val wasUp = _linkState.value == true
                        _linkState.value = newLinkUp
                        if (newLinkUp == true && !wasUp) {
                            // Down->up transition mid-capture (e.g. a link flap) - rerun
                            // the RX-enable kick, same as the initial bring-up does.
                            driver.onLinkUp(connection, ::logDiag)
                        }
                    }
                }

                if (bytesRead > 0) {
                    consecutiveErrors = 0
                    totalBytesRead += bytesRead
                    val headHex = readBuffer.take(Math.min(24, bytesRead)).joinToString("") { String.format("%02X ", it) }
                    logDiag("RAW USB RX ($bytesRead B, total $totalBytesRead B): $headHex")

                    // A single bulk read can contain several aggregated frames (the
                    // vendor RX path packs multiple Ethernet frames per URB); parsing
                    // only the first one silently dropped the rest.
                    val packets = PacketParser.parseAggregateFrames(readBuffer, bytesRead)
                    for (packet in packets) {
                        logDiag("SUCCESS: Parsed ${packet.protocol} frame from ${packet.srcMac} to ${packet.dstMac}")
                        packet.lldpFrame?.let { lldp ->
                            logDiag("LLDP TLVs: ${lldp.tlvs.joinToString(", ") { "type=${it.type} len=${it.length}" }}")
                            logDiag("LLDP portDescription=${lldp.portDescription}")
                        }
                        _capturedPackets.emit(packet)
                    }
                } else if (bytesRead < 0) {
                    consecutiveErrors++
                    if (now - lastLogTime >= 15000) { // Log once every 15 seconds
                        lastLogTime = now
                        val elapsedSec = (now - startTime) / 1000
                        logDiag("Polling active on EP 0x${String.format("%02X", endpointIn.address)} (waiting for frames... ${elapsedSec}s elapsed)")
                    }

                    if (consecutiveErrors > 5) {
                        delay(200) // Prevent fast-spin logging on endpoint errors
                    } else {
                        delay(50)
                    }

                    if (consecutiveErrors > 200) {
                        logDiag("ERROR: Excessive consecutive USB bulkTransfer errors (>200). Connection likely lost.")
                        break
                    }
                } else {
                    delay(50)
                }
            }
        }
    }

    fun stopCapture() {
        Log.i(TAG, "Stopping USB packet capture...")
        captureJob?.cancel()
        captureJob = null

        activeConnection?.let { conn ->
            for (iface in claimedInterfaces) {
                try {
                    conn.releaseInterface(iface)
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing interface ${iface.id}", e)
                }
            }
            try {
                conn.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing UsbDeviceConnection", e)
            }
        }
        claimedInterfaces.clear()
        activeConnection = null
        activeDevice = null
        activeVendorDriver = null
        _linkState.value = null
        _connectionState.value = UsbConnectionState.Disconnected
    }

    fun updateState(newState: UsbConnectionState) {
        _connectionState.value = newState
    }
}
