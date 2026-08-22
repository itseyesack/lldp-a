package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.net.lldpsniffer.usb.PeerDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PeerSortMode(val label: String) {
    LAST_SEEN("Last seen"),
    FIRST_SEEN("First seen"),
    ALPHANUMERIC("A-Z")
}

@Composable
fun PeerDevicesCard(
    peers: List<PeerDevice>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(PeerSortMode.LAST_SEEN) }
    val sortedPeers = remember(peers, sortMode) {
        when (sortMode) {
            PeerSortMode.LAST_SEEN -> peers.sortedByDescending { it.lastSeen }
            PeerSortMode.FIRST_SEEN -> peers.sortedBy { it.firstSeen }
            PeerSortMode.ALPHANUMERIC -> peers.sortedBy { it.mac }
        }
    }

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
                        imageVector = Icons.Default.Lan,
                        contentDescription = "Peer Devices",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Peer Devices (${peers.size})",
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

                if (peers.isEmpty()) {
                    Text(
                        text = "No other Ethernet traffic observed yet on this link.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PeerSortMode.entries.forEach { mode ->
                            FilterChip(
                                selected = sortMode == mode,
                                onClick = { sortMode = mode },
                                label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(sortedPeers, key = { it.mac }) { peer ->
                            PeerDeviceRow(peer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerDeviceRow(peer: PeerDevice) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.mac,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${peer.ip ?: "IP unknown"} · ${peer.protocolLabel} · last seen ${timeFormat.format(Date(peer.lastSeen))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text(
            text = "${peer.frameCount}",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
