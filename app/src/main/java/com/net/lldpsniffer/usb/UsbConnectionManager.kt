package com.net.lldpsniffer.usb

import android.content.Context
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import com.net.lldpsniffer.model.CapturedPacket
import com.net.lldpsniffer.parser.PacketParser
import com.net.lldpsniffer.usb.driver.AsixAx88179Driver
import com.net.lldpsniffer.usb.driver.AsixAx88772Driver
import com.net.lldpsniffer.usb.driver.LinkStatus
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

/** Static identity of the connected adapter, for an info panel - not live register state. */
data class AdapterInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val driverName: String?,
    val maxLinkMbps: Int?,
    val supported: Boolean
)

/** A directly-connected host observed passively via any Ethernet frame it sent, not just LLDP/CDP. */
data class PeerDevice(
    val mac: String,
    val ip: String?,
    val protocolLabel: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val frameCount: Int
)

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

        // Some flaky adapters/cables lose the underlying USB connection mid-capture (every
        // control transfer starts failing, no more data ever arrives) without Android ever
        // reporting a real ACTION_USB_DEVICE_DETACHED for it - confirmed on hardware where
        // UsbHostManager's own log showed no "Removed device" event during the outage. Since
        // no detach broadcast is coming, the app has to notice the dead connection itself and
        // recover by reopening the same UsbDevice, instead of spinning on bulkTransfer forever.
        private const val CONSECUTIVE_LINK_POLL_FAILURE_THRESHOLD = 3
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 750L
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

    // Speed/duplex detail alongside the plain up/down _linkState above - populated by the
    // same poll, kept separate so existing _linkState consumers are unaffected.
    private val _linkStatus = MutableStateFlow<LinkStatus?>(null)
    val linkStatus: StateFlow<LinkStatus?> = _linkStatus.asStateFlow()

    private val _adapterInfo = MutableStateFlow<AdapterInfo?>(null)
    val adapterInfo: StateFlow<AdapterInfo?> = _adapterInfo.asStateFlow()

    // Keyed by MAC so repeated frames from the same peer update one entry instead of
    // growing unbounded; cleared on stopCapture like the other per-session state above.
    private val peerDevicesByMac = mutableMapOf<String, PeerDevice>()
    private val _peerDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peerDevices: StateFlow<List<PeerDevice>> = _peerDevices.asStateFlow()

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
     * Reads link status via the vendor driver, logging (rather than silently
     * swallowing) a control-transfer failure. readLinkStatus() returns null on
     * such a failure with no logging of its own - callers must not treat that
     * null as "link is down"; it only means this particular poll didn't get a
     * reading, and should keep whatever state they already trust.
     */
    private fun VendorAdapterDriver.readLinkStatusLogged(
        connection: UsbDeviceConnection,
        failureContext: String
    ): LinkStatus? {
        val status = readLinkStatus(connection, ::logDiag)
        if (status == null) {
            logDiag("$failureContext: link status read failed (control transfer error).")
        }
        return status
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

    private suspend fun runCaptureSetup(device: UsbDevice, reconnectAttempt: Int = 0) {
        var openedConnection: UsbDeviceConnection? = null
        try {
            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("usbManager.openDevice returned null. USB Host permission might be missing.")
            openedConnection = connection

            activeConnection = connection
            activeVendorDriver = null
            claimedInterfaces.clear()
            logDiag("UsbDeviceConnection opened successfully.")

            val matchedInfoDriver = vendorDrivers.firstOrNull { it.matches(device) }
            _adapterInfo.value = AdapterInfo(
                deviceName = device.deviceName ?: "USB Ethernet Adapter",
                vendorId = device.vendorId,
                productId = device.productId,
                driverName = matchedInfoDriver?.name,
                maxLinkMbps = matchedInfoDriver?.maxLinkMbps,
                supported = matchedInfoDriver != null
            )

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
                        val initialStatus = matchedDriver.readLinkStatusLogged(connection, "Initial link status read")
                        _linkStatus.value = initialStatus
                        _linkState.value = initialStatus?.up
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
            val connectionLost = runIngestionLoop(connection, epIn, activeVendorDriver)

            if (connectionLost) {
                teardownForReconnect(connection)
                scheduleReconnectOrGiveUp(
                    device,
                    reconnectAttempt,
                    "Connection to the adapter appears dead (no data or register access for a sustained " +
                        "period, with no USB detach event)"
                )
            }

        } catch (e: Exception) {
            // A reopen can fail the exact same way the original connection died (hardware
            // still wedged right after reopening, not merely a transient blip) - treating this
            // identically to a mid-capture connection loss, rather than giving up on the first
            // failed reopen, gives the device more chances to settle before we stop retrying.
            openedConnection?.let { teardownForReconnect(it) }
            scheduleReconnectOrGiveUp(
                device,
                reconnectAttempt,
                "Error during USB capture setup: ${e.localizedMessage}"
            )
        }
    }

    private suspend fun scheduleReconnectOrGiveUp(device: UsbDevice, reconnectAttempt: Int, reason: String) {
        if (activeDevice != device) {
            // Superseded by a manual stopCapture() or a different device taking over while this
            // attempt was in flight - respect whatever state that already produced instead of
            // clobbering it with a stale Error/Connecting update for a device we no longer own.
            return
        }
        if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
            val nextAttempt = reconnectAttempt + 1
            logDiag("$reason - reopening the device automatically (attempt $nextAttempt/$MAX_RECONNECT_ATTEMPTS).")
            _connectionState.value = UsbConnectionState.Connecting("Reconnecting to adapter (attempt $nextAttempt/$MAX_RECONNECT_ATTEMPTS)...")
            delay(RECONNECT_DELAY_MS)
            if (activeDevice == device) {
                runCaptureSetup(device, nextAttempt)
            }
        } else {
            logDiag("ERROR: Automatic recovery exhausted after $reconnectAttempt attempt(s) - giving up until the adapter is unplugged and replugged. $reason")
            clearConnectionResources()
            _connectionState.value = UsbConnectionState.Error(message = reason)
        }
    }

    /** @return true if the loop exited because the connection appears dead and should be reopened. */
    private suspend fun runIngestionLoop(connection: UsbDeviceConnection, endpointIn: UsbEndpoint, driver: VendorAdapterDriver?): Boolean {
        val readBuffer = ByteArray(20480) // 20KB buffer - matches AX88179's recommended rx_urb_size
        logDiag("Starting Bulk IN packet ingestion loop on EP Address 0x${String.format("%02X", endpointIn.address)} (Buffer=20KB)...")

        val startTime = System.currentTimeMillis()
        var lastLogTime = System.currentTimeMillis()
        var lastLinkPollTime = System.currentTimeMillis()
        var totalBytesRead = 0L
        var consecutiveErrors = 0
        var consecutiveLinkPollFailures = 0
        var connectionLost = false

        withContext(Dispatchers.IO) {
            while (isActive && activeConnection == connection) {
                val bytesRead = connection.bulkTransfer(endpointIn, readBuffer, readBuffer.size, 1000)
                val now = System.currentTimeMillis()

                // Keep the link indicator live for the whole capture, not just at setup -
                // a cable can be plugged/unplugged or (re)negotiate mid-capture. Only log
                // when the state actually changes, to avoid spamming the log every 2s.
                if (driver != null && now - lastLinkPollTime >= 2000) {
                    lastLinkPollTime = now
                    val newStatus = driver.readLinkStatusLogged(connection, "Link status poll")
                    if (newStatus == null) {
                        // A failed register read (transient USB/control-transfer hiccup) is
                        // not evidence the link actually went down. Previously this overwrote a
                        // confirmed "up" state with null/Unknown on a single flaky poll, even
                        // though the link never moved. Keep the last known-good state instead.
                        consecutiveLinkPollFailures++
                        if (consecutiveLinkPollFailures >= CONSECUTIVE_LINK_POLL_FAILURE_THRESHOLD) {
                            logDiag(
                                "ERROR: $consecutiveLinkPollFailures consecutive link status polls failed - " +
                                    "the USB connection is no longer responding to control transfers."
                            )
                            connectionLost = true
                            break
                        }
                    } else {
                        consecutiveLinkPollFailures = 0
                        _linkStatus.value = newStatus
                        val newLinkUp = newStatus.up
                        if (newLinkUp != _linkState.value) {
                            val wasUp = _linkState.value == true
                            _linkState.value = newLinkUp
                            if (newLinkUp && !wasUp) {
                                // Down->up transition mid-capture (e.g. a link flap) - rerun
                                // the RX-enable kick, same as the initial bring-up does.
                                driver.onLinkUp(connection, ::logDiag)
                            }
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
                        // Full field dump (not just TLV headers) so a capture can be fully
                        // debugged from the exported log alone - the USB-C port is occupied
                        // by the adapter during capture, so live adb inspection isn't an
                        // option while a session is running.
                        packet.lldpFrame?.let { lldp ->
                            logDiag("LLDP TLVs: ${lldp.tlvs.joinToString(", ") { "type=${it.type} len=${it.length}" }}")
                            logDiag(
                                "LLDP fields: chassisId=${lldp.chassisId} (subtype=${lldp.chassisIdSubtype}), " +
                                    "portId=${lldp.portId} (subtype=${lldp.portIdSubtype}), ttl=${lldp.ttl}, " +
                                    "portDescription=${lldp.portDescription}, systemName=${lldp.systemName}, " +
                                    "systemDescription=${lldp.systemDescription}, systemCapabilities=${lldp.systemCapabilities}, " +
                                    "managementAddress=${lldp.managementAddress}, vlanId=${lldp.vlanId}"
                            )
                        }
                        packet.cdpFrame?.let { cdp ->
                            logDiag(
                                "CDP fields: deviceId=${cdp.deviceId}, portId=${cdp.portId}, platform=${cdp.platform}, " +
                                    "addresses=${cdp.addresses}, duplex=${cdp.duplex}, nativeVlan=${cdp.nativeVlan}, " +
                                    "capabilities=${cdp.capabilities}, softwareVersion=${cdp.softwareVersion}, " +
                                    "ttl=${cdp.ttl}, version=${cdp.version}"
                            )
                        }
                        _capturedPackets.emit(packet)
                    }

                    PacketParser.parseGenericFrame(readBuffer, bytesRead)?.let { peer ->
                        val existing = peerDevicesByMac[peer.srcMac]
                        peerDevicesByMac[peer.srcMac] = PeerDevice(
                            mac = peer.srcMac,
                            ip = peer.srcIp ?: existing?.ip,
                            protocolLabel = peer.protocolLabel,
                            firstSeen = existing?.firstSeen ?: now,
                            lastSeen = now,
                            frameCount = (existing?.frameCount ?: 0) + 1
                        )
                        _peerDevices.value = peerDevicesByMac.values.sortedByDescending { it.lastSeen }
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
                        connectionLost = true
                        break
                    }
                } else {
                    delay(50)
                }
            }
        }
        return connectionLost
    }

    /**
     * Releases and closes a connection that runIngestionLoop determined is dead, without
     * touching activeDevice/link-state/history/peer-list - unlike stopCapture(), this is not
     * a user-visible disconnect, just clearing the way for runCaptureSetup() to reopen the
     * same UsbDevice and rebuild everything from scratch as an automatic recovery attempt.
     */
    private fun teardownForReconnect(connection: UsbDeviceConnection) {
        // A plain close()+reopen() was observed on hardware to be insufficient - the reopened
        // connection immediately failed every register write, meaning the chip itself was still
        // wedged rather than merely the app's handle to it. A real port-level reset (equivalent
        // to a physical unplug/replug) gives the upcoming reopen an actual chance to succeed.
        val resetOk = UsbBusReset.reset(connection)
        logDiag(
            if (resetOk) "Issued a USB port reset before reopening."
            else "USB port reset unavailable or failed - falling back to a plain close+reopen."
        )
        synchronized(this) {
            for (iface in claimedInterfaces) {
                try {
                    connection.releaseInterface(iface)
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing interface ${iface.id} during reconnect", e)
                }
            }
            try {
                connection.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing UsbDeviceConnection during reconnect", e)
            }
            claimedInterfaces.clear()
            if (activeConnection == connection) {
                activeConnection = null
            }
            activeVendorDriver = null
        }
    }

    fun stopCapture() {
        Log.i(TAG, "Stopping USB packet capture...")
        clearConnectionResources()
        _connectionState.value = UsbConnectionState.Disconnected
    }

    // startCapture() mutates this same state under synchronized(this); without the same
    // lock here, a fast unplug/replug (DETACHED->stopCapture, ATTACHED->startCapture in
    // quick succession) could interleave the two, e.g. this call nulling out
    // activeConnection/claimedInterfaces out from under a startCapture() that just claimed
    // them - leaving the ingestion loop's `activeConnection == connection` guard checking a
    // connection that was swapped or closed underneath it. Callers set _connectionState
    // themselves afterward (Disconnected for a user-facing stop, Error when automatic
    // recovery gives up) since that differs by caller.
    private fun clearConnectionResources() {
        synchronized(this) {
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
        }
        _linkState.value = null
        _linkStatus.value = null
        _adapterInfo.value = null
        peerDevicesByMac.clear()
        _peerDevices.value = emptyList()
    }

    fun updateState(newState: UsbConnectionState) {
        _connectionState.value = newState
    }
}
