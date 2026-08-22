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
import com.net.lldpsniffer.usb.UsbConnectionState
import com.net.lldpsniffer.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermission: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val linkState by viewModel.linkState.collectAsState()
    val currentRecord by viewModel.currentRecord.collectAsState()
    val currentRecordFinalized by viewModel.currentRecordFinalized.collectAsState()
    val history by viewModel.history.collectAsState()
    val filteredPackets by viewModel.filteredPackets.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val selectedPacket by viewModel.selectedPacket.collectAsState()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsState()
    val copyFieldsConfig by viewModel.copyFieldsConfig.collectAsState()

    var showHelpDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<com.net.lldpsniffer.model.MergedSwitchportRecord?>(null) }

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
                    IconButton(onClick = { showSettingsDialog = true }) {
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
                onRequestPermission = onRequestPermission,
                onStartCapture = onStartCapture,
                onStopCapture = onStopCapture
            )

            // Switchport Card (live merged LLDP/CDP record for the current session)
            SwitchportCard(
                info = currentRecord,
                linkUp = linkState,
                recordFinalized = currentRecordFinalized,
                copyFieldsConfig = copyFieldsConfig,
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
                copyFieldsConfig = copyFieldsConfig
            )

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

    selectedPacket?.let { packet ->
        PacketDetailDialog(
            packet = packet,
            onDismiss = { viewModel.selectPacket(null) }
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

    if (showSettingsDialog) {
        SettingsDialog(
            copyFieldsConfig = copyFieldsConfig,
            onDismiss = { showSettingsDialog = false },
            onConfigChange = { viewModel.updateCopyFieldsConfig(it) },
            onClearAllHistory = { viewModel.clearAllHistory() }
        )
    }
}

@Composable
fun ConnectionStatusBanner(
    state: UsbConnectionState,
    linkUp: Boolean?,
    onRequestPermission: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit
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
                    is UsbConnectionState.Connected -> Color(0xFF4CAF50)
                    is UsbConnectionState.Error -> Color(0xFFF44336)
                    is UsbConnectionState.Connecting -> Color(0xFFFFC107)
                    else -> Color.Gray
                }

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = statusColor, shape = CircleShape)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    val titleText = when (state) {
                        is UsbConnectionState.Connected -> "CDC-ECM Config 2 Active"
                        is UsbConnectionState.Connecting -> "Connecting USB Adapter..."
                        is UsbConnectionState.DeviceDetected -> "USB Ethernet Adapter Attached"
                        is UsbConnectionState.PermissionRequested -> "Awaiting USB Permission"
                        is UsbConnectionState.PermissionDenied -> "USB Permission Denied"
                        is UsbConnectionState.Error -> "USB Error / Contention"
                        is UsbConnectionState.Disconnected -> "No USB Adapter Connected"
                    }

                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val subText = when (state) {
                        is UsbConnectionState.Connected -> "${state.deviceName} (EP 0x${String.format("%02X", state.bulkInEndpoint)})"
                        is UsbConnectionState.Connecting -> state.stepDescription
                        is UsbConnectionState.DeviceDetected -> "${state.deviceName} (VID 0x${String.format("%04X", state.vendorId)} / PID 0x${String.format("%04X", state.productId)})"
                        is UsbConnectionState.Error -> state.message
                        else -> "Plug in a USB-C Ethernet Adapter (e.g. Realtek RTL8153)"
                    }

                    Text(
                        text = subText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )

                    if (state is UsbConnectionState.Connected) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val linkColor = when (linkUp) {
                                true -> Color(0xFF4CAF50)
                                false -> Color(0xFFF44336)
                                null -> Color.Gray
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(color = linkColor, shape = CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (linkUp) {
                                    true -> "PHY Link: Up"
                                    false -> "PHY Link: Down"
                                    null -> "PHY Link: Unknown"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            when (state) {
                is UsbConnectionState.Connected -> {
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
