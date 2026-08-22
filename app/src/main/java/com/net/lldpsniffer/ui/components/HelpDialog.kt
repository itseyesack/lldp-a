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
private const val USAGE_DOC_URL = "$GITHUB_REPO_URL/blob/main/docs/USAGE.md"
private const val WEBHOOK_DOC_URL = "$GITHUB_REPO_URL/blob/main/docs/WEBHOOK_TEMPLATES.md"

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

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HelpSection(
                        title = "1. Theory of Operation",
                        description = "This app opens the USB Ethernet adapter directly via the Android UsbManager Host API, bypassing the kernel's r8152/CDC-ECM network driver entirely - no root required. Adapters with a known vendor-specific chipset (see Supported Hardware) are brought up through their own register map for full frame access; other CDC-ECM-compatible dongles fall back to a control-transfer-based packet filter (0x1F SET_ETHERNET_PACKET_FILTER) to forward multicast/promiscuous frames to user space."
                    )

                    HelpSection(
                        title = "2. Supported Hardware",
                        description = "Modular vendor chipset drivers:\n" +
                                "• Realtek RTL8153 / RTL8152 / RTL8156\n" +
                                "• ASIX AX88179 / AX88178A (USB 3.0 Gigabit)\n" +
                                "• ASIX AX88772 / AX88772A / AX88772B (USB 2.0 Fast Ethernet), including Apple's A1277 \"Apple USB Ethernet Adapter\" rebrand\n" +
                                "Other generic USB CDC-ECM Ethernet dongles may work via the fallback path above."
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

                    HelpSection(
                        title = "5. Settings",
                        description = "Settings (gear icon) lets you reorder, relabel, and toggle which fields are captured to the clipboard, choose a copy format (Basic/Markdown/JSON), send every completed session to a webhook (Discord-compatible by default, with a custom JSON template), and control history retention."
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Documentation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        DocLink(text = "View source on GitHub", url = GITHUB_REPO_URL, uriHandler = uriHandler)
                        DocLink(text = "Usage guide (docs/USAGE.md)", url = USAGE_DOC_URL, uriHandler = uriHandler)
                        DocLink(text = "Webhook templates (docs/WEBHOOK_TEMPLATES.md)", url = WEBHOOK_DOC_URL, uriHandler = uriHandler)
                    }
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

@Composable
private fun DocLink(text: String, url: String, uriHandler: androidx.compose.ui.platform.UriHandler) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { uriHandler.openUri(url) }
    )
}
