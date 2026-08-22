package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.net.lldpsniffer.usb.AdapterInfo
import com.net.lldpsniffer.usb.driver.LinkStatus
import java.util.Locale

@Composable
fun AdapterInfoDialog(
    adapterInfo: AdapterInfo,
    linkStatus: LinkStatus?,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Adapter Info",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailSectionHeader("Identity")
                DetailRow("Device Name", adapterInfo.deviceName)
                DetailRow("Vendor ID", "0x${String.format(Locale.US, "%04X", adapterInfo.vendorId)}")
                DetailRow("Product ID", "0x${String.format(Locale.US, "%04X", adapterInfo.productId)}")
                DetailRow("Driver", adapterInfo.driverName ?: "None (unrecognized chipset)")
                DetailRow(
                    "Status",
                    if (adapterInfo.supported) "Supported" else "Unsupported - best-effort only"
                )

                Spacer(modifier = Modifier.height(8.dp))

                DetailSectionHeader("Link")
                DetailRow("Max Link Speed", adapterInfo.maxLinkMbps?.let { "$it Mbps" } ?: "Unknown")
                DetailRow("Current State", if (linkStatus?.up == true) "Up" else "Down / Unknown")
                DetailRow("Negotiated Speed", linkStatus?.speedMbps?.let { "$it Mbps" } ?: "N/A")
                DetailRow("Duplex", linkStatus?.duplex ?: "N/A")
            }
        }
    }
}
