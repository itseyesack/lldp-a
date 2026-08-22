package com.net.lldpsniffer.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.net.lldpsniffer.ui.components.*
import com.net.lldpsniffer.usb.AdapterInfo
import com.net.lldpsniffer.usb.UsbConnectionState
import com.net.lldpsniffer.usb.driver.LinkStatus
import com.net.lldpsniffer.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermission: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val linkState by viewModel.linkState.collectAsState()
    val linkStatus by viewModel.linkStatus.collectAsState()
    val adapterInfo by viewModel.adapterInfo.collectAsState()
    val peerDevices by viewModel.peerDevices.collectAsState()
    val currentRecord by viewModel.currentRecord.collectAsState()
    val currentRecordFinalized by viewModel.currentRecordFinalized.collectAsState()
    val soloHostPeer by viewModel.soloHostPeer.collectAsState()
    val history by viewModel.history.collectAsState()
    val filteredPackets by viewModel.filteredPackets.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val selectedPacket by viewModel.selectedPacket.collectAsState()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsState()
    val copyFieldsConfig by viewModel.copyFieldsConfig.collectAsState()
    val copyFormat by viewModel.copyFormat.collectAsState()
    val showLogViews by viewModel.showLogViews.collectAsState()

    var showHelpDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<com.net.lldpsniffer.model.MergedSwitchportRecord?>(null) }
    var detailTarget by remember { mutableStateOf<com.net.lldpsniffer.model.MergedSwitchportRecord?>(null) }
    var showAdapterInfoDialog by remember { mutableStateOf(false) }

    fun shareJsonUri(uri: android.net.Uri?, title: String) {
        if (uri == null) return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, title))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = "USB Icon",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "lldp-a",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "Help"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Status Banner
            ConnectionStatusBanner(
                state = connectionState,
                linkUp = linkState,
                linkStatus = linkStatus,
                adapterInfo = adapterInfo,
                onRequestPermission = onRequestPermission,
                onStartCapture = onStartCapture,
                onStopCapture = onStopCapture,
                onShowAdapterInfo = { showAdapterInfoDialog = true }
            )

            // Switchport Card (live merged LLDP/CDP record for the current session)
            SwitchportCard(
                info = currentRecord,
                linkUp = linkState,
                recordFinalized = currentRecordFinalized,
                soloHostPeer = soloHostPeer,
                copyFieldsConfig = copyFieldsConfig,
                copyFormat = copyFormat,
                onRenameClick = { currentRecord?.let { renameTarget = it } },
                onEndRecordClick = { viewModel.endCurrentRecordManually() }
            )

            // History of past records
            HistoryCard(
                records = history,
                onExportAll = { shareJsonUri(viewModel.exportHistoryToJson(context), "Share Record History") },
                onExportRecord = { record -> shareJsonUri(viewModel.exportRecordToJson(context, record), "Share Record") },
                onRenameRecord = { record -> renameTarget = record },
                onDeleteRecord = { record -> viewModel.deleteHistoryRecord(record.id) },
                onRecordClick = { record -> detailTarget = record },
                copyFieldsConfig = copyFieldsConfig,
                copyFormat = copyFormat
            )

            // Passively-observed directly-connected peers (any Ethernet traffic, not just LLDP/CDP)
            PeerDevicesCard(peers = peerDevices)

            if (showLogViews) {
                // Live Hardware Log Console
                DiagnosticLogsCard(
                    logs = diagnosticLogs,
                    onExportLogs = {
                        val uri = viewModel.exportHardwareLogs(context)
                        if (uri != null) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Export Hardware Diagnostic Logs"))
                        }
                    },
                    onClearLogs = { viewModel.clearHardwareLogs() }
                )

                // Raw Packet Log (collapsed by default)
                PacketLogCard(
                    packets = filteredPackets,
                    selectedFilter = selectedFilter,
                    onFilterSelected = { viewModel.setFilter(it) },
                    onClearClick = { viewModel.clearPackets() },
                    onExportClick = { shareJsonUri(viewModel.exportPacketsToJson(context), "Share Captured Packets") },
                    onPacketClick = { viewModel.selectPacket(it) }
                )
            }
        }
    }

    selectedPacket?.let { packet ->
        PacketDetailDialog(
            packet = packet,
            onDismiss = { viewModel.selectPacket(null) }
        )
    }

    if (showAdapterInfoDialog) {
        adapterInfo?.let { info ->
            AdapterInfoDialog(
                adapterInfo = info,
                linkStatus = linkStatus,
                onDismiss = { showAdapterInfoDialog = false }
            )
        }
    }

    detailTarget?.let { record ->
        RecordDetailDialog(
            record = record,
            copyFieldsConfig = copyFieldsConfig,
            copyFormat = copyFormat,
            onDismiss = { detailTarget = null }
        )
    }

    renameTarget?.let { record ->
        RenameRecordDialog(
            initialName = record.name ?: "",
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameRecord(record.id, newName)
                renameTarget = null
            }
        )
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }
}

@Composable
fun ConnectionStatusBanner(
    state: UsbConnectionState,
    linkUp: Boolean?,
    linkStatus: LinkStatus?,
    adapterInfo: AdapterInfo?,
    onRequestPermission: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onShowAdapterInfo: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        // Background stays constant regardless of state - text colors below are tuned for
        // surfaceVariant, and the previous per-state light tints (e.g. pale green/red) made
        // the onSurface/onSurfaceVariant text unreadable. Only the status dot changes color.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusColor = when (state) {
                    is UsbConnectionState.Disconnected -> Color.Gray
                    is UsbConnectionState.Error -> Color(0xFFF44336)
                    is UsbConnectionState.PermissionDenied -> Color(0xFFF44336)
                    is UsbConnectionState.Connecting -> Color(0xFFFFC107)
                    is UsbConnectionState.DeviceDetected -> Color(0xFFFFC107)
                    is UsbConnectionState.PermissionRequested -> Color(0xFFFFC107)
                    is UsbConnectionState.Connected -> when (linkUp) {
                        true -> Color(0xFF4CAF50)
                        false -> Color(0xFFFF9800)
                        null -> Color(0xFFFFC107)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = statusColor, shape = CircleShape)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    val titleText = when (state) {
                        is UsbConnectionState.Connected -> "Adapter Connected"
                        is UsbConnectionState.Connecting -> "Connecting..."
                        is UsbConnectionState.DeviceDetected -> "Adapter Detected"
                        is UsbConnectionState.PermissionRequested -> "Permission Needed"
                        is UsbConnectionState.PermissionDenied -> "Permission Denied"
                        is UsbConnectionState.Error -> "Connection Error"
                        is UsbConnectionState.Disconnected -> "No Adapter Connected"
                    }

                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val subText = when (state) {
                        is UsbConnectionState.Connected -> when (linkUp) {
                            true -> linkStatus?.speedMbps?.let { "Link: Up - $it Mbps" } ?: "Link: Up"
                            false -> "Link: Down"
                            null -> "Link: Unknown"
                        }
                        is UsbConnectionState.Connecting -> state.stepDescription
                        is UsbConnectionState.DeviceDetected -> "Ready to scan"
                        is UsbConnectionState.PermissionRequested -> "Allow access to use this adapter"
                        is UsbConnectionState.PermissionDenied -> "Unplug and reconnect the adapter to try again"
                        is UsbConnectionState.Error -> state.message
                        is UsbConnectionState.Disconnected -> "Plug in a USB-C Ethernet adapter"
                    }

                    Text(
                        text = subText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            when (state) {
                is UsbConnectionState.Connected -> {
                    if (adapterInfo != null) {
                        IconButton(onClick = onShowAdapterInfo) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Adapter Info"
                            )
                        }
                    }
                    IconButton(onClick = onStopCapture) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Capture",
                            tint = Color(0xFFD32F2F)
                        )
                    }
                }
                is UsbConnectionState.DeviceDetected -> {
                    Button(onClick = onStartCapture) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start")
                    }
                }
                is UsbConnectionState.PermissionRequested -> {
                    Button(onClick = onRequestPermission) {
                        Text("Grant")
                    }
                }
                else -> {
                    Button(onClick = onStartCapture) {
                        Text("Scan")
                    }
                }
            }
        }
    }
}
