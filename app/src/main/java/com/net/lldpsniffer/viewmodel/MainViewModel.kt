package com.net.lldpsniffer.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.net.lldpsniffer.data.RecordStore
import com.net.lldpsniffer.data.SettingsStore
import com.net.lldpsniffer.model.CapturedPacket
import com.net.lldpsniffer.model.CopyFieldsConfig
import com.net.lldpsniffer.model.CopyFormat
import com.net.lldpsniffer.model.MergedSwitchportRecord
import com.net.lldpsniffer.model.ProtocolType
import com.net.lldpsniffer.model.WebhookConfig
import com.net.lldpsniffer.model.isComplete
import com.net.lldpsniffer.model.mergeWithPacket
import com.net.lldpsniffer.model.toJson
import com.net.lldpsniffer.service.PacketSnifferService
import com.net.lldpsniffer.usb.AdapterInfo
import com.net.lldpsniffer.usb.PeerDevice
import com.net.lldpsniffer.usb.UsbConnectionState
import com.net.lldpsniffer.usb.driver.LinkStatus
import com.net.lldpsniffer.webhook.WebhookSender
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class PacketFilter {
    ALL,
    LLDP,
    CDP
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val recordStore = RecordStore(application)
    private val settingsStore = SettingsStore(application)

    private val _service = MutableStateFlow<PacketSnifferService?>(null)

    val connectionState: StateFlow<UsbConnectionState> = _service.flatMapLatest { service ->
        service?.connectionState ?: flowOf(UsbConnectionState.Disconnected)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsbConnectionState.Disconnected)

    val diagnosticLogs: StateFlow<List<String>> = _service.flatMapLatest { service ->
        service?.diagnosticLogs ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val linkState: StateFlow<Boolean?> = _service.flatMapLatest { service ->
        service?.linkState ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val linkStatus: StateFlow<LinkStatus?> = _service.flatMapLatest { service ->
        service?.linkStatus ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val adapterInfo: StateFlow<AdapterInfo?> = _service.flatMapLatest { service ->
        service?.adapterInfo ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val peerDevices: StateFlow<List<PeerDevice>> = _service.flatMapLatest { service ->
        service?.peerDevices ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _packetList = MutableStateFlow<List<CapturedPacket>>(emptyList())
    val packetList: StateFlow<List<CapturedPacket>> = _packetList.asStateFlow()

    private val _selectedFilter = MutableStateFlow(PacketFilter.ALL)
    val selectedFilter: StateFlow<PacketFilter> = _selectedFilter.asStateFlow()

    val filteredPackets: StateFlow<List<CapturedPacket>> = combine(_packetList, _selectedFilter) { list, filter ->
        when (filter) {
            PacketFilter.ALL -> list
            PacketFilter.LLDP -> list.filter { it.protocol == ProtocolType.LLDP }
            PacketFilter.CDP -> list.filter { it.protocol == ProtocolType.CDP }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPacket = MutableStateFlow<CapturedPacket?>(null)
    val selectedPacket: StateFlow<CapturedPacket?> = _selectedPacket.asStateFlow()

    private val _history = MutableStateFlow(recordStore.load().take(100))
    val history: StateFlow<List<MergedSwitchportRecord>> = _history.asStateFlow()

    private val _currentRecord = MutableStateFlow<MergedSwitchportRecord?>(null)
    val currentRecord: StateFlow<MergedSwitchportRecord?> = _currentRecord.asStateFlow()

    private val _currentRecordFinalized = MutableStateFlow(false)
    val currentRecordFinalized: StateFlow<Boolean> = _currentRecordFinalized.asStateFlow()

    private val _copyFieldsConfig = MutableStateFlow(settingsStore.loadCopyFieldsConfig())
    val copyFieldsConfig: StateFlow<CopyFieldsConfig> = _copyFieldsConfig.asStateFlow()

    private val _copyFormat = MutableStateFlow(settingsStore.loadCopyFormat())
    val copyFormat: StateFlow<CopyFormat> = _copyFormat.asStateFlow()

    private val _webhookConfig = MutableStateFlow(settingsStore.loadWebhookConfig())
    val webhookConfig: StateFlow<WebhookConfig> = _webhookConfig.asStateFlow()

    private val _showLogViews = MutableStateFlow(settingsStore.loadShowLogViews())
    val showLogViews: StateFlow<Boolean> = _showLogViews.asStateFlow()

    private val _soloHostPeer = MutableStateFlow<PeerDevice?>(null)
    val soloHostPeer: StateFlow<PeerDevice?> = _soloHostPeer.asStateFlow()

    private var currentSessionId: String? = null
    private var sessionFinalizedThisSession = false
    private var linkDownDebounceJob: Job? = null
    private var soloHostCheckJob: Job? = null

    private var packetCollectionJob: Job? = null
    private var linkStateCollectionJob: Job? = null

    fun bindService(service: PacketSnifferService) {
        _service.value = service
        // Cancel any collector from a prior bind (e.g. service reconnect after a crash) so
        // we never end up with two concurrent collectors double-inserting every packet.
        packetCollectionJob?.cancel()
        packetCollectionJob = viewModelScope.launch {
            service.capturedPackets.collect { packet ->
                // An uncaught exception here would cancel this coroutine per Flow semantics,
                // permanently killing packet display for the rest of the session while the
                // service's independent diagnostic log kept confirming reception - silently
                // swallowing one bad packet is safer than losing the whole collector.
                try {
                    _packetList.update { current ->
                        (listOf(packet) + current).take(200) // Keep latest 200 packets
                    }
                    onPacketForRecord(packet)
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "Error handling captured packet", e)
                }
            }
        }

        linkStateCollectionJob?.cancel()
        linkStateCollectionJob = viewModelScope.launch {
            service.linkState.collect { onLinkStateChanged(it) }
        }
    }

    fun unbindService() {
        packetCollectionJob?.cancel()
        packetCollectionJob = null
        linkStateCollectionJob?.cancel()
        linkStateCollectionJob = null
        _service.value = null
    }

    private fun onLinkStateChanged(linkUp: Boolean?) {
        if (linkUp == true) {
            linkDownDebounceJob?.cancel()
            linkDownDebounceJob = null
            if (currentSessionId == null) {
                currentSessionId = UUID.randomUUID().toString()
                sessionFinalizedThisSession = false
                _currentRecord.value = null
                _currentRecordFinalized.value = false
                _soloHostPeer.value = null
                soloHostCheckJob?.cancel()
                // If a full minute passes with no LLDP/CDP frame but exactly one other host is
                // seen on the link, this is very likely a direct connection to an end device
                // (laptop, phone) rather than a switch, which would normally emit frames within
                // 30-60s - so show "connected to a host" instead of an indefinite spinner.
                soloHostCheckJob = viewModelScope.launch {
                    delay(60_000)
                    if (_currentRecord.value == null) {
                        peerDevices.value.singleOrNull()?.let { _soloHostPeer.value = it }
                    }
                }
            }
        } else {
            if (currentSessionId != null && linkDownDebounceJob == null) {
                linkDownDebounceJob = viewModelScope.launch {
                    delay(5000)
                    finalizeSession()
                }
            }
        }
    }

    private fun onPacketForRecord(packet: CapturedPacket) {
        val sessionId = currentSessionId ?: return
        if (packet.lldpFrame == null && packet.cdpFrame == null) return

        val base = _currentRecord.value ?: MergedSwitchportRecord(id = sessionId, startTime = System.currentTimeMillis())
        val merged = base.mergeWithPacket(packet)
        _currentRecord.value = merged
        soloHostCheckJob?.cancel()
        _soloHostPeer.value = null

        // Dumps the post-merge record so a bad field can be traced from the exported log
        // alone - the USB-C port is occupied by the adapter during capture, so live adb
        // inspection of app state isn't an option while a session is running.
        _service.value?.usbConnectionManager?.logDiag(
            "Merged record: switchName=${merged.switchName}, portId=${merged.portId}, " +
                "chassisId=${merged.chassisId}, vlanId=${merged.vlanId}, managementIp=${merged.managementIp}, " +
                "duplex=${merged.duplex}, platform=${merged.platform}, softwareVersion=${merged.softwareVersion}, " +
                "capabilities=${merged.capabilities}, portDescription=${merged.portDescription}"
        )

        if (!sessionFinalizedThisSession && merged.isComplete()) {
            pushToHistory(merged)
            sessionFinalizedThisSession = true
            _currentRecordFinalized.value = true
        }
    }

    private fun pushToHistory(record: MergedSwitchportRecord) {
        val finalized = record.copy(endTime = record.endTime ?: System.currentTimeMillis())
        _history.update { current ->
            val withoutDuplicate = current.filterNot { it.id == record.id }
            (listOf(finalized) + withoutDuplicate).take(100)
        }
        recordStore.save(_history.value)
        sendSessionWebhook(finalized)
    }

    private fun sendSessionWebhook(record: MergedSwitchportRecord) {
        val config = _webhookConfig.value
        if (!config.enabled || config.url.isBlank()) return
        viewModelScope.launch {
            val result = WebhookSender.send(config, record, _copyFieldsConfig.value)
            val logSink = _service.value?.usbConnectionManager
            if (result.success) {
                logSink?.logDiag("Webhook sent successfully (HTTP ${result.httpCode})")
            } else {
                logSink?.logDiag("Webhook send failed: ${result.errorMessage}")
            }
        }
    }

    private fun finalizeSession() {
        val record = _currentRecord.value
        if (record != null && !sessionFinalizedThisSession && record.packetCount > 0) {
            pushToHistory(record)
        }
        currentSessionId = null
        sessionFinalizedThisSession = false
        linkDownDebounceJob = null
        soloHostCheckJob?.cancel()
        soloHostCheckJob = null
        _currentRecord.value = null
        _currentRecordFinalized.value = false
        _soloHostPeer.value = null
    }

    fun endCurrentRecordManually() {
        val record = _currentRecord.value ?: return
        if (sessionFinalizedThisSession) return
        pushToHistory(record)
        sessionFinalizedThisSession = true
        _currentRecordFinalized.value = true
    }

    fun renameRecord(id: String, newName: String) {
        _currentRecord.value?.let { current ->
            if (current.id == id) {
                _currentRecord.value = current.copy(name = newName)
            }
        }
        var changed = false
        _history.update { current ->
            current.map {
                if (it.id == id) {
                    changed = true
                    it.copy(name = newName)
                } else it
            }
        }
        if (changed) recordStore.save(_history.value)
    }

    fun deleteHistoryRecord(id: String) {
        _history.update { current -> current.filterNot { it.id == id } }
        recordStore.save(_history.value)
    }

    fun clearAllHistory() {
        _history.value = emptyList()
        recordStore.save(emptyList())
    }

    fun updateCopyFieldsConfig(config: CopyFieldsConfig) {
        _copyFieldsConfig.value = config
        settingsStore.saveCopyFieldsConfig(config)
    }

    fun setCopyFormat(format: CopyFormat) {
        _copyFormat.value = format
        settingsStore.saveCopyFormat(format)
    }

    fun updateWebhookConfig(config: WebhookConfig) {
        _webhookConfig.value = config
        settingsStore.saveWebhookConfig(config)
    }

    fun sendTestWebhook(onResult: (WebhookSender.Result) -> Unit) {
        val sample = MergedSwitchportRecord(
            id = "test",
            name = "Test Webhook Record",
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis(),
            switchName = "test-switch.example.com",
            portId = "GigabitEthernet1/0/1",
            chassisId = "Example-Switch-Model",
            vlanId = 10,
            managementIp = "192.0.2.1",
            duplex = "Full",
            portDescription = "Test Interface Description",
            systemDescription = "Example system description string",
            platform = "Example Platform",
            softwareVersion = "1.0.0",
            capabilities = "Switch, Router",
            ttlSeconds = 120,
            packetCount = 1,
            hasLldp = true,
            hasCdp = true
        )
        viewModelScope.launch {
            val result = WebhookSender.send(_webhookConfig.value, sample, _copyFieldsConfig.value)
            onResult(result)
        }
    }

    fun setShowLogViews(show: Boolean) {
        _showLogViews.value = show
        settingsStore.saveShowLogViews(show)
    }

    fun setFilter(filter: PacketFilter) {
        _selectedFilter.value = filter
    }

    fun selectPacket(packet: CapturedPacket?) {
        _selectedPacket.value = packet
    }

    fun clearPackets() {
        _packetList.value = emptyList()
        _selectedPacket.value = null
    }

    fun exportHardwareLogs(context: Context): Uri? {
        val file = _service.value?.usbConnectionManager?.exportLogsToFile()
            ?: run {
                val fallbackFile = File(context.cacheDir, "l2_hardware_logs.txt")
                if (fallbackFile.exists()) fallbackFile else null
            }
        if (file == null || !file.exists()) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun clearHardwareLogs() {
        _service.value?.usbConnectionManager?.clearDiagLogs()
    }

    fun exportPacketsToJson(context: Context): Uri? {
        val packets = _packetList.value
        if (packets.isEmpty()) return null

        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "l2_packets_${dateFormat.format(Date())}.json"
        val file = File(context.cacheDir, fileName)

        val jsonBuilder = StringBuilder()
        jsonBuilder.append("[\n")
        packets.forEachIndexed { index, pkt ->
            jsonBuilder.append("  {\n")
            jsonBuilder.append("    \"id\": \"${pkt.id}\",\n")
            jsonBuilder.append("    \"timestamp\": ${pkt.timestamp},\n")
            jsonBuilder.append("    \"protocol\": \"${pkt.protocol}\",\n")
            jsonBuilder.append("    \"srcMac\": \"${pkt.srcMac}\",\n")
            jsonBuilder.append("    \"dstMac\": \"${pkt.dstMac}\",\n")
            jsonBuilder.append("    \"length\": ${pkt.length}\n")
            jsonBuilder.append("  }${if (index < packets.size - 1) "," else ""}\n")
        }
        jsonBuilder.append("]\n")

        file.writeText(jsonBuilder.toString())
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun exportRecordToJson(context: Context, record: MergedSwitchportRecord): Uri? {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "l2_record_${dateFormat.format(Date())}.json"
        val file = File(context.cacheDir, fileName)
        file.writeText(record.toJson().toString(2))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun exportHistoryToJson(context: Context): Uri? {
        val records = _history.value
        if (records.isEmpty()) return null
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "l2_history_${dateFormat.format(Date())}.json"
        val file = File(context.cacheDir, fileName)
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        file.writeText(array.toString(2))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    override fun onCleared() {
        finalizeSession()
        super.onCleared()
    }
}
