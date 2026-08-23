package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.net.lldpsniffer.model.CopyFieldsConfig
import com.net.lldpsniffer.model.CopyFormat
import com.net.lldpsniffer.model.MergedSwitchportRecord
import com.net.lldpsniffer.model.ProtocolType
import com.net.lldpsniffer.model.toCopyText
import com.net.lldpsniffer.usb.PeerDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SwitchportCard(
    info: MergedSwitchportRecord?,
    linkUp: Boolean? = true,
    recordFinalized: Boolean = false,
    soloHostPeer: PeerDevice? = null,
    copyFieldsConfig: CopyFieldsConfig = CopyFieldsConfig(),
    copyFormat: CopyFormat = CopyFormat.BASIC,
    onRenameClick: () -> Unit = {},
    onEndRecordClick: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = "Switch Icon",
                        tint = if (linkUp == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Switchport Discovery",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (linkUp == true && info != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (info.hasLldp) ProtocolBadge(protocol = ProtocolType.LLDP)
                        if (info.hasCdp) {
                            Spacer(modifier = Modifier.width(4.dp))
                            ProtocolBadge(protocol = ProtocolType.CDP)
                        }
                        if (recordFinalized) {
                            Spacer(modifier = Modifier.width(4.dp))
                            FinalizedBadge()
                        }
                    }
                }
            }

            if (linkUp == true && info != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(info.toCopyText(copyFieldsConfig, copyFormat))) }) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy to Clipboard")
                    }
                    IconButton(onClick = onRenameClick) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Rename / Save")
                    }
                    IconButton(onClick = onEndRecordClick, enabled = !recordFinalized) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "End Record",
                            tint = if (recordFinalized) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (linkUp != true) {
                // Collapsed/idle: no link means no discovery frames are possible over this
                // adapter, so a live spinner here would just be misleading busywork.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color = MaterialTheme.colorScheme.outline, shape = androidx.compose.foundation.shape.CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Idle - waiting for PHY link before listening for switch frames",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else if (info == null && soloHostPeer != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Connected to host",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Connected directly to a host device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No switch found - ${soloHostPeer.mac}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else if (info == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Awaiting LLDP / CDP frame from network switch...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "(LLDP/CDP frames arrive every 30-60 seconds)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                // Full-width, stacked fields: this data comes from real network gear (FQDNs,
                // "GigabitEthernetX/X/X" port names, long IOS version strings) that routinely
                // overflows a cramped two-column tile layout. Only the genuinely short fields
                // (VLAN ID / Duplex) are still paired on one row.
                InfoTile(label = "Switch Hostname", value = info.switchName ?: "Unknown", singleLine = false)
                Spacer(modifier = Modifier.height(10.dp))
                InfoTile(label = "Port ID", value = info.portId ?: "Unknown", singleLine = false)

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    InfoTile(
                        label = "VLAN ID",
                        value = info.vlanId?.toString() ?: "Untagged / Native",
                        valueColor = if (info.vlanId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    InfoTile(
                        label = "Duplex",
                        value = info.duplex ?: "Auto / Full",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                InfoTile(label = "Management IP", value = info.managementIp ?: "N/A", singleLine = false)
                Spacer(modifier = Modifier.height(10.dp))
                InfoTile(label = "Chassis ID / Model", value = info.chassisId ?: "Unknown", singleLine = false)
                Spacer(modifier = Modifier.height(10.dp))
                InfoTile(label = "Interface Description", value = info.portDescription ?: "Unknown", singleLine = false)

                val sysDesc = info.systemDescription
                if (!sysDesc.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "System Description",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = sysDesc,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(info.startTime))
                Text(
                    text = "Session started at $timeStr (${info.packetCount} packets, TTL: ${info.ttlSeconds ?: "?"}s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun InfoTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    singleLine: Boolean = true
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            maxLines = if (singleLine) 1 else 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
fun FinalizedBadge() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(color = Color(0xFF43A047), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Session complete",
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
fun ProtocolBadge(protocol: ProtocolType) {
    val (bgColor, textColor, text) = when (protocol) {
        ProtocolType.LLDP -> Triple(Color(0xFF1E88E5), Color.White, "LLDP")
        ProtocolType.CDP -> Triple(Color(0xFFE53935), Color.White, "CDP")
        ProtocolType.UNKNOWN -> Triple(Color.Gray, Color.White, "RAW")
    }

    Box(
        modifier = Modifier
            .background(color = bgColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}
