package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.net.lldpsniffer.model.CopyFieldsConfig
import com.net.lldpsniffer.model.MergedSwitchportRecord
import com.net.lldpsniffer.model.displayTitle
import com.net.lldpsniffer.model.toDisplayText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordDetailDialog(
    record: MergedSwitchportRecord,
    copyFieldsConfig: CopyFieldsConfig,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val dateFormat = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = record.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(record.toDisplayText(copyFieldsConfig)))
                    }) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy to Clipboard")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (record.hasLldp) ProtocolBadge(protocol = com.net.lldpsniffer.model.ProtocolType.LLDP)
                    if (record.hasCdp) {
                        Spacer(modifier = Modifier.width(4.dp))
                        ProtocolBadge(protocol = com.net.lldpsniffer.model.ProtocolType.CDP)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        DetailSectionHeader("Switchport")
                        DetailRow("Switch Hostname", record.switchName ?: "Unknown")
                        DetailRow("Port ID", record.portId ?: "Unknown")
                        DetailRow("Chassis ID / Model", record.chassisId ?: "Unknown")
                        DetailRow("VLAN ID", record.vlanId?.toString() ?: "N/A")
                        DetailRow("Management IP", record.managementIp ?: "N/A")
                        DetailRow("Duplex", record.duplex ?: "N/A")
                        DetailRow("Interface Description", record.portDescription ?: "N/A")
                    }

                    item {
                        DetailSectionHeader("System")
                        DetailRow("System Description", record.systemDescription ?: "N/A")
                        DetailRow("Platform", record.platform ?: "N/A")
                        DetailRow("Software Version", record.softwareVersion ?: "N/A")
                        DetailRow("Capabilities", record.capabilities ?: "N/A")
                    }

                    item {
                        DetailSectionHeader("Session")
                        DetailRow("Packet Count", record.packetCount.toString())
                        DetailRow("TTL", record.ttlSeconds?.let { "${it}s" } ?: "N/A")
                        DetailRow("Start Time", dateFormat.format(Date(record.startTime)))
                        DetailRow("End Time", record.endTime?.let { dateFormat.format(Date(it)) } ?: "Ongoing")
                    }
                }
            }
        }
    }
}
