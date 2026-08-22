package com.net.lldpsniffer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.net.lldpsniffer.data.SettingsStore
import com.net.lldpsniffer.model.CopyFormat
import com.net.lldpsniffer.model.WebhookConfig
import com.net.lldpsniffer.ui.components.CopyFieldsSection
import com.net.lldpsniffer.viewmodel.MainViewModel
import com.net.lldpsniffer.webhook.WebhookSender

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val copyFieldsConfig by viewModel.copyFieldsConfig.collectAsState()
    val copyFormat by viewModel.copyFormat.collectAsState()
    val webhookConfig by viewModel.webhookConfig.collectAsState()
    val showLogViews by viewModel.showLogViews.collectAsState()
    val historyLimit by viewModel.historyLimit.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }
    var historyLimitInput by remember {
        mutableStateOf(if (historyLimit == SettingsStore.HISTORY_LIMIT_UNLIMITED) "" else historyLimit.toString())
    }
    var testResult by remember { mutableStateOf<WebhookSender.Result?>(null) }
    var testInFlight by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(16.dp)
        ) {
            CopyFieldsSection(
                config = copyFieldsConfig,
                onConfigChange = { viewModel.updateCopyFieldsConfig(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Copy Format",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CopyFormat.entries.forEachIndexed { index, format ->
                    SegmentedButton(
                        selected = copyFormat == format,
                        onClick = { viewModel.setCopyFormat(format) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = CopyFormat.entries.size)
                    ) {
                        Text(
                            when (format) {
                                CopyFormat.BASIC -> "Basic"
                                CopyFormat.MARKDOWN -> "Markdown"
                                CopyFormat.JSON -> "JSON"
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Webhook",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Send every session to a webhook", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = webhookConfig.enabled,
                    onCheckedChange = { viewModel.updateWebhookConfig(webhookConfig.copy(enabled = it)) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webhookConfig.url,
                onValueChange = { viewModel.updateWebhookConfig(webhookConfig.copy(url = it)) },
                label = { Text("Webhook URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webhookConfig.deviceName,
                onValueChange = { viewModel.updateWebhookConfig(webhookConfig.copy(deviceName = it)) },
                label = { Text("Device name (optional)") },
                supportingText = { Text("Shown as the sender name for Discord-style webhooks; also available as {{device_name}} in custom templates.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webhookConfig.authHeaderName,
                onValueChange = { viewModel.updateWebhookConfig(webhookConfig.copy(authHeaderName = it)) },
                label = { Text("Auth header name (optional)") },
                placeholder = { Text("e.g. Authorization") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = webhookConfig.authHeaderValue,
                onValueChange = { viewModel.updateWebhookConfig(webhookConfig.copy(authHeaderValue = it)) },
                label = { Text("Auth header value (optional)") },
                placeholder = { Text("e.g. Bearer <token>") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Use custom JSON template", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = webhookConfig.useCustomTemplate,
                    onCheckedChange = { viewModel.updateWebhookConfig(webhookConfig.copy(useCustomTemplate = it)) }
                )
            }

            if (webhookConfig.useCustomTemplate) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = webhookConfig.template,
                    onValueChange = { viewModel.updateWebhookConfig(webhookConfig.copy(template = it)) },
                    label = { Text("JSON template") },
                    supportingText = { Text("See docs/WEBHOOK_TEMPLATES.md for the full placeholder reference.") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = {
                    viewModel.updateWebhookConfig(webhookConfig.copy(template = WebhookConfig.DEFAULT_DISCORD_TEMPLATE))
                }) {
                    Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset to Discord default")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    testInFlight = true
                    testResult = null
                    viewModel.sendTestWebhook { result ->
                        testResult = result
                        testInFlight = false
                    }
                },
                enabled = !testInFlight && webhookConfig.url.isNotBlank()
            ) {
                Text(if (testInFlight) "Sending..." else "Send test webhook")
            }

            testResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (result.success) {
                        "Success (HTTP ${result.httpCode})"
                    } else {
                        "Failed: ${result.errorMessage ?: "Unknown error"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "History",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Keep all history (no limit)", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = historyLimit == SettingsStore.HISTORY_LIMIT_UNLIMITED,
                    onCheckedChange = { checked ->
                        if (checked) {
                            viewModel.setHistoryLimit(SettingsStore.HISTORY_LIMIT_UNLIMITED)
                        } else {
                            val restored = historyLimitInput.toIntOrNull()?.coerceAtLeast(1)
                                ?: SettingsStore.DEFAULT_HISTORY_LIMIT
                            historyLimitInput = restored.toString()
                            viewModel.setHistoryLimit(restored)
                        }
                    }
                )
            }

            if (historyLimit != SettingsStore.HISTORY_LIMIT_UNLIMITED) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = historyLimitInput,
                    onValueChange = { text ->
                        val digitsOnly = text.filter { it.isDigit() }
                        historyLimitInput = digitsOnly
                        digitsOnly.toIntOrNull()?.let { parsed ->
                            if (parsed >= 1) viewModel.setHistoryLimit(parsed)
                        }
                    },
                    label = { Text("Max saved sessions") },
                    supportingText = { Text("Oldest sessions are dropped once this limit is reached.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Every completed session is kept until you clear history manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Diagnostics",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Show log views", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = showLogViews, onCheckedChange = { viewModel.setShowLogViews(it) })
            }

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
                        viewModel.clearAllHistory()
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

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
