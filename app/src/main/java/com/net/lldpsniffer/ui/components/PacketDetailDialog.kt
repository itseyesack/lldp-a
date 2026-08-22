package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.net.lldpsniffer.model.CapturedPacket
import com.net.lldpsniffer.model.ProtocolType
import java.util.Locale

@Composable
fun PacketDetailDialog(
    packet: CapturedPacket,
    onDismiss: () -> Unit
) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProtocolBadge(protocol = packet.protocol)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Frame Details (${packet.length} B)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        DetailSectionHeader("Ethernet Header")
                        DetailRow("Source MAC", packet.srcMac)
                        DetailRow("Destination MAC", packet.dstMac)
                        DetailRow("Frame Length", "${packet.length} bytes")
                    }

                    if (packet.protocol == ProtocolType.LLDP && packet.lldpFrame != null) {
                        val lldp = packet.lldpFrame
                        item {
                            DetailSectionHeader("LLDP Fields (IEEE 802.1AB)")
                            DetailRow("System Name", lldp.systemName ?: "N/A")
                            DetailRow("Port ID", lldp.portId ?: "N/A")
                            DetailRow("Chassis ID", lldp.chassisId ?: "N/A")
                            DetailRow("VLAN ID", lldp.vlanId?.toString() ?: "N/A")
                            DetailRow("Management Address", lldp.managementAddress ?: "N/A")
                            DetailRow("TTL", lldp.ttl?.toString() ?: "N/A")
                            DetailRow("Port Description", lldp.portDescription ?: "N/A")
                            DetailRow("System Description", lldp.systemDescription ?: "N/A")
                            DetailRow("Capabilities", lldp.systemCapabilities ?: "N/A")
                        }

                        item {
                            DetailSectionHeader("LLDP TLVs (${lldp.tlvs.size})")
                        }

                        items(lldp.tlvs) { tlv ->
                            TlvCard(
                                title = "TLV Type ${tlv.type} (Len: ${tlv.length})",
                                hexValue = tlv.rawValue.joinToString("") { String.format(Locale.US, "%02X ", it) },
                                asciiValue = String(tlv.rawValue, Charsets.UTF_8).filter { it >= ' ' && it <= '~' }
                            )
                        }
                    }

                    if (packet.protocol == ProtocolType.CDP && packet.cdpFrame != null) {
                        val cdp = packet.cdpFrame
                        item {
                            DetailSectionHeader("CDP Fields (Cisco Proprietary)")
                            DetailRow("Device ID", cdp.deviceId ?: "N/A")
                            DetailRow("Port ID", cdp.portId ?: "N/A")
                            DetailRow("Native VLAN", cdp.nativeVlan?.toString() ?: "N/A")
                            DetailRow("Duplex", cdp.duplex ?: "N/A")
                            DetailRow("Platform", cdp.platform ?: "N/A")
                            DetailRow("Addresses", cdp.addresses.joinToString(", ").ifEmpty { "N/A" })
                            DetailRow("TTL", "${cdp.ttl}s")
                            DetailRow("Version", cdp.version.toString())
                            DetailRow("Software Version", cdp.softwareVersion ?: "N/A")
                        }

                        item {
                            DetailSectionHeader("CDP TLVs (${cdp.tlvs.size})")
                        }

                        items(cdp.tlvs) { tlv ->
                            TlvCard(
                                title = "TLV Type 0x${String.format(Locale.US, "%04X", tlv.type)} (Len: ${tlv.length})",
                                hexValue = tlv.rawValue.joinToString("") { String.format(Locale.US, "%02X ", it) },
                                asciiValue = String(tlv.rawValue, Charsets.UTF_8).filter { it >= ' ' && it <= '~' }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun TlvCard(title: String, hexValue: String, asciiValue: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            if (asciiValue.isNotBlank()) {
                Text(
                    text = "ASCII: $asciiValue",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                text = "HEX: $hexValue",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
