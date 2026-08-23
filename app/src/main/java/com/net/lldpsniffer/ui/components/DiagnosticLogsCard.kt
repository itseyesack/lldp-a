package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

@Composable
fun DiagnosticLogsCard(
    logs: StateFlow<List<String>>,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // Collected here rather than hoisted up into MainScreen: during an active capture this
    // can update dozens of times per second (raw hex dump + per-field dumps per packet), and
    // collecting it at the MainScreen level forced the ENTIRE screen (history, peers, packet
    // log, etc.) to recompose on every single log line - a real ANR risk during bursts.
    // Scoping the collection to just this card limits that recomposition to the log list.
    val logList by logs.collectAsState()

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
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Console Logs",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live Hardware Logs (${logList.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expanded) {
                        TextButton(
                            onClick = onExportLogs,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Export Logs", fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = onClearLogs,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Clear", fontSize = 12.sp)
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand"
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFF121212), shape = RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (logList.isEmpty()) {
                        Text(
                            text = "No diagnostic events logged yet.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(logList) { logMsg ->
                                Text(
                                    text = logMsg,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (logMsg.contains("ERROR")) Color(0xFFFF5252)
                                    else if (logMsg.contains("SUCCESS") || logMsg.contains("Read ")) Color(0xFF69F0AE)
                                    else Color(0xFFE0E0E0),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
