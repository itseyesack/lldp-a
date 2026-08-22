package com.net.lldpsniffer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.net.lldpsniffer.model.CopyFieldConfig
import com.net.lldpsniffer.model.CopyFieldsConfig
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyColumnState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CopyFieldsSection(
    config: CopyFieldsConfig,
    onConfigChange: (CopyFieldsConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CopyFieldConfig?>(null) }
    var fields by remember(config) { mutableStateOf(config.fields) }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyColumnState(lazyListState) { from, to ->
        fields = fields.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        onConfigChange(CopyFieldsConfig(fields))
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Fields included when copying a record",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "Expand"
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Drag to reorder. Order and format here also control webhook summary placeholders.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(fields, key = { it.id.name }) { field ->
                    ReorderableItem(reorderableState, key = field.id.name) { _ ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .draggableHandle()
                            )
                            Checkbox(
                                checked = field.enabled,
                                onCheckedChange = { checked ->
                                    fields = fields.map { if (it.id == field.id) it.copy(enabled = checked) else it }
                                    onConfigChange(CopyFieldsConfig(fields))
                                }
                            )
                            Text(
                                text = field.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { editTarget = field }, modifier = Modifier.size(36.dp)) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit label", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    editTarget?.let { field ->
        EditFieldLabelDialog(
            initialLabel = field.label,
            onDismiss = { editTarget = null },
            onConfirm = { newLabel ->
                val trimmed = newLabel.trim()
                fields = fields.map {
                    if (it.id == field.id) it.copy(label = trimmed.ifEmpty { field.id.defaultLabel() }) else it
                }
                onConfigChange(CopyFieldsConfig(fields))
                editTarget = null
            }
        )
    }
}

@Composable
private fun EditFieldLabelDialog(
    initialLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialLabel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit field label") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Label") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
