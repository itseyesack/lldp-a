package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.net.lldpsniffer.model.CopyFieldsConfig

@Composable
fun SettingsDialog(
    copyFieldsConfig: CopyFieldsConfig,
    onDismiss: () -> Unit,
    onConfigChange: (CopyFieldsConfig) -> Unit,
    onClearAllHistory: () -> Unit
) {
    var config by remember(copyFieldsConfig) { mutableStateOf(copyFieldsConfig) }
    var showClearConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Fields included when copying a record",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))

                CopyFieldToggle("Switch Hostname", config.switchName) { config = config.copy(switchName = it); onConfigChange(config) }
                CopyFieldToggle("Port ID", config.portId) { config = config.copy(portId = it); onConfigChange(config) }
                CopyFieldToggle("Chassis ID / Model", config.chassisId) { config = config.copy(chassisId = it); onConfigChange(config) }
                CopyFieldToggle("VLAN ID", config.vlanId) { config = config.copy(vlanId = it); onConfigChange(config) }
                CopyFieldToggle("Management IP", config.managementIp) { config = config.copy(managementIp = it); onConfigChange(config) }
                CopyFieldToggle("Duplex", config.duplex) { config = config.copy(duplex = it); onConfigChange(config) }
                CopyFieldToggle("Interface Description", config.portDescription) { config = config.copy(portDescription = it); onConfigChange(config) }
                CopyFieldToggle("System Description", config.systemDescription) { config = config.copy(systemDescription = it); onConfigChange(config) }
                CopyFieldToggle("Platform", config.platform) { config = config.copy(platform = it); onConfigChange(config) }
                CopyFieldToggle("Software Version", config.softwareVersion) { config = config.copy(softwareVersion = it); onConfigChange(config) }
                CopyFieldToggle("Capabilities", config.capabilities) { config = config.copy(capabilities = it); onConfigChange(config) }
                CopyFieldToggle("Protocols Seen", config.protocols) { config = config.copy(protocols = it); onConfigChange(config) }
                CopyFieldToggle("Packet Count", config.packetCount) { config = config.copy(packetCount = it); onConfigChange(config) }
                CopyFieldToggle("Timestamps", config.timestamps) { config = config.copy(timestamps = it); onConfigChange(config) }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                if (showClearConfirm) {
                    Text(
                        text = "Delete all saved history? This can't be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            onClearAllHistory()
                            showClearConfirm = false
                        }) {
                            Text("Confirm Delete", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { showClearConfirm = false }) {
                            Text("Cancel")
                        }
                    }
                } else {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All History", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun CopyFieldToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
