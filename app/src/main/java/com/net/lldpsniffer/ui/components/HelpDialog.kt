package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private const val GITHUB_REPO_URL = "https://github.com/itseyesack/lldp-a"

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "lldp-a Info & Setup",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "View source on GitHub",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(GITHUB_REPO_URL) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HelpSection(
                        title = "1. Theory of Operation (No-Root)",
                        description = "Standard Android kernels claim USB Ethernet dongles as network interfaces (r8152 driver). This app bypasses root requirements by opening the USB device via the Android UsbManager Host API, switching the chip to Configuration 2 (CDC-ECM), and sending a SET_ETHERNET_PACKET_FILTER control transfer (0x1F) to forward multicast/promiscuous frames to user space."
                    )

                    HelpSection(
                        title = "2. Supported Hardware",
                        description = "Tested primarily on Realtek RTL8153 (VID 0x0BDA / PID 0x8153) USB Gigabit Ethernet adapters. Also supports RTL8152, RTL8156, and generic USB CDC-ECM Ethernet dongles with CDC configuration values."
                    )

                    HelpSection(
                        title = "3. Troubleshooting Kernel Contention",
                        description = "If Android's kernel refuses to yield control of the dongle:\n" +
                                "• Go to Android Settings -> Network & Internet -> Hotspot & Tethering, and ensure Ethernet Tethering is OFF.\n" +
                                "• Unplug the USB-C adapter, wait 3 seconds, and plug it back in.\n" +
                                "• Grant USB host permission when prompted by the app."
                    )

                    HelpSection(
                        title = "4. Switchport Discovery Delay",
                        description = "Managed Ethernet switches typically send LLDP and CDP frames every 30 to 60 seconds. Leave the adapter plugged into an active switch port for up to 60 seconds to capture the first frame."
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Got it")
                }
            }
        }
    }
}

@Composable
fun HelpSection(title: String, description: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
