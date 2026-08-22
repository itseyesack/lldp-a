package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.net.lldpsniffer.model.CapturedPacket
import com.net.lldpsniffer.model.ProtocolType
import com.net.lldpsniffer.viewmodel.PacketFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PacketLogCard(
    packets: List<CapturedPacket>,
    selectedFilter: PacketFilter,
    onFilterSelected: (PacketFilter) -> Unit,
    onClearClick: () -> Unit,
    onExportClick: () -> Unit,
    onPacketClick: (CapturedPacket) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = "Packet Log",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Packet Log (${packets.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand"
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                PacketFilterBar(
                    selectedFilter = selectedFilter,
                    onFilterSelected = onFilterSelected,
                    onClearClick = onClearClick,
                    onExportClick = onExportClick
                )
                PacketLogList(packets = packets, onPacketClick = onPacketClick)
            }
        }
    }
}

@Composable
fun PacketFilterBar(
    selectedFilter: PacketFilter,
    onFilterSelected: (PacketFilter) -> Unit,
    onClearClick: () -> Unit,
    onExportClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PacketFilter.values().forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { onFilterSelected(filter) },
                    label = { Text(filter.name) }
                )
            }
        }

        Row {
            TextButton(onClick = onExportClick) {
                Text("Export")
            }
            TextButton(onClick = onClearClick) {
                Text("Clear")
            }
        }
    }
}

@Composable
fun PacketLogList(
    packets: List<CapturedPacket>,
    onPacketClick: (CapturedPacket) -> Unit
) {
    if (packets.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No captured L2 packets matching filter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(packets, key = { it.id }) { packet ->
                PacketItemCard(packet = packet, onClick = { onPacketClick(packet) })
            }
        }
    }
}

@Composable
fun PacketItemCard(
    packet: CapturedPacket,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolBadge(protocol = packet.protocol)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Src: ${packet.srcMac}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                val subtitle = when (packet.protocol) {
                    ProtocolType.LLDP -> "Sys: ${packet.lldpFrame?.systemName ?: "N/A"} | Port: ${packet.lldpFrame?.portId ?: "N/A"} | VLAN: ${packet.lldpFrame?.vlanId ?: "None"}"
                    ProtocolType.CDP -> "Dev: ${packet.cdpFrame?.deviceId ?: "N/A"} | Port: ${packet.cdpFrame?.portId ?: "N/A"} | Native VLAN: ${packet.cdpFrame?.nativeVlan ?: "None"}"
                    ProtocolType.UNKNOWN -> "Dst: ${packet.dstMac}"
                }

                Text(
                    text = subtitle,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(packet.timestamp))
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "${packet.length} bytes",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
