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
import com.net.lldpsniffer.model.MergedSwitchportRecord
import com.net.lldpsniffer.model.ProtocolType
import com.net.lldpsniffer.model.isComplete
import com.net.lldpsniffer.model.mergeWithPacket
import com.net.lldpsniffer.model.toJson
import com.net.lldpsniffer.service.PacketSnifferService
import com.net.lldpsniffer.usb.UsbConnectionState
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

    private val _showLogViews = MutableStateFlow(settingsStore.loadShowLogViews())
    val showLogViews: StateFlow<Boolean> = _showLogViews.asStateFlow()

    private var currentSessionId: String? = null
    private var sessionFinalizedThisSession = false
    private var linkDownDebounceJob: Job? = null

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

        if (!sessionFinalizedThisSession && merged.isComplete()) {
            pushToHistory(merged)
            sessionFinalizedThisSession = true
            _currentRecordFinalized.value = true
        }
    }

    private fun pushToHistory(record: MergedSwitchportRecord) {
        _history.update { current ->
            val withoutDuplicate = current.filterNot { it.id == record.id }
            (listOf(record.copy(endTime = record.endTime ?: System.currentTimeMillis())) + withoutDuplicate).take(100)
        }
        recordStore.save(_history.value)
    }

    private fun finalizeSession() {
        val record = _currentRecord.value
        if (record != null && !sessionFinalizedThisSession && record.packetCount > 0) {
            pushToHistory(record)
        }
        currentSessionId = null
        sessionFinalizedThisSession = false
        linkDownDebounceJob = null
        _currentRecord.value = null
        _currentRecordFinalized.value = false
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
