package com.jarves.mh.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.model.Project
import com.jarves.mh.tokens.StoredToken
import com.jarves.mh.tokens.TokenPreset
import com.jarves.mh.tokens.TokenPresets
import com.jarves.mh.tokens.TokenRotationNotice
import com.jarves.mh.ui.theme.PocketGreen
import com.jarves.mh.ui.theme.PocketOrange

/**
 * Settings → Tokens (Phase 2): the encrypted tokens vault UI. Values are
 * never shown — only a static mask plus the env var name. The model never
 * sees values either (SecretRedactor); the UI wording mirrors that promise.
 */
@Composable
fun TokensSection(
    tokens: List<StoredToken>,
    projects: List<Project>,
    testRunningId: String?,
    testResults: Map<String, String>,
    testValid: Map<String, Boolean>,
    rotationNotice: TokenRotationNotice?,
    onAddToken: (presetId: String, name: String, envVar: String, value: String) -> Unit,
    onUpdateTokenValue: (id: String, value: String) -> Unit,
    onRemoveToken: (id: String) -> Unit,
    onTestToken: (id: String) -> Unit,
    onMarkRotated: (id: String) -> Unit,
    onSetTokenEnabled: (id: String, enabled: Boolean) -> Unit,
    onSetTokenAutoDisable: (id: String, on: Boolean) -> Unit,
    onSetTokenAllowedProjects: (id: String, projects: Set<String>) -> Unit,
    onDismissRotationNotice: () -> Unit,
) {
    val context = LocalContext.current
    var addDialogPreset by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTokenId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingTokenId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPresetPicker by rememberSaveable { mutableStateOf(false) }

    rotationNotice?.let { notice ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PocketOrange.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, Modifier.size(15.dp), tint = PocketOrange)
                    Spacer(Modifier.width(7.dp))
                    Text("Rotate your tokens", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Text(notice.message, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    notice.items.take(3).forEach { item ->
                        item.managementUrl?.let { url ->
                            OutlinedButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            }) {
                                Text("Rotate ${item.title}", fontSize = 12.sp)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(13.dp))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    notice.items.forEach { item ->
                        TextButton(onClick = { onMarkRotated(item.tokenId) }) { Text("Rotated: ${item.title}", fontSize = 11.sp) }
                    }
                }
                TextButton(onClick = onDismissRotationNotice) { Text("Dismiss") }
            }
        }
    }

    if (tokens.isEmpty()) {
        Text(
            "No tokens stored yet. Add a provider token and it is injected as an environment variable when a task " +
                "needs it — the value stays on this device, is encrypted with the Android keystore, and is masked " +
                "out of anything that leaves the app. The model only ever sees the variable NAME.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    tokens.forEach { token ->
        val preset = TokenPresets.byId(token.presetId)
        val testLine = testResults[token.id]
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(PocketGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(preset.title.take(1), color = PocketGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            token.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (token.needsRotation) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(5.dp), color = PocketOrange.copy(alpha = 0.16f)) {
                                Text(
                                    "needs rotation",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PocketOrange,
                                )
                            }
                        }
                        if (!token.enabled) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(
                                    "off",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        buildString {
                            append("${token.envVar} = ••••••••")
                            if (token.autoDisableAfterTask) append(" · auto-off after task")
                            append(" · ")
                            append(
                                when {
                                    token.allowedProjects.isEmpty() -> "all projects"
                                    else -> "${token.allowedProjects.size} of ${projects.size} projects"
                                },
                            )
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = token.enabled, onCheckedChange = { onSetTokenEnabled(token.id, it) })
            }
            token.rotationReason?.let { reason ->
                Text(reason, fontSize = 11.sp, color = PocketOrange)
            }
            testLine?.let { line ->
                Text(
                    line,
                    fontSize = 11.sp,
                    color = if (testValid[token.id] == true) PocketGreen else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (testRunningId == token.id) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                } else if (preset.test != null) {
                    TextButton(onClick = { onTestToken(token.id) }) { Text("Test token", fontSize = 11.sp) }
                } else {
                    TextButton(onClick = { onTestToken(token.id) }) { Text("Test token", fontSize = 11.sp) }
                }
                TextButton(onClick = { editingTokenId = token.id }) {
                    Icon(Icons.Default.Edit, null, Modifier.size(13.dp)); Spacer(Modifier.width(3.dp)); Text("Edit", fontSize = 11.sp)
                }
                if (token.needsRotation) {
                    TextButton(onClick = { onMarkRotated(token.id) }) { Text("Rotated", fontSize = 11.sp) }
                }
                TextButton(onClick = { deletingTokenId = token.id }) {
                    Icon(Icons.Default.Delete, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Delete", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }

    OutlinedButton(onClick = { showPresetPicker = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, null, Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add token")
    }

    if (showPresetPicker) {
        AlertDialog(
            onDismissRequest = { showPresetPicker = false },
            title = { Text("Which provider?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TokenPresets.ALL.forEach { preset ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPresetPicker = false
                                    addDialogPreset = preset.id
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    if (preset.id == TokenPresets.CUSTOM_ID) preset.title else "${preset.title} · ${preset.envVars.first()}",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    preset.hint,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPresetPicker = false }) { Text("Cancel") } },
        )
    }

    addDialogPreset?.let { presetId -> AddTokenDialog(preset = TokenPresets.byId(presetId), onAdd = onAddToken, onDismiss = { addDialogPreset = null }) }

    editingTokenId?.let { id ->
        tokens.firstOrNull { it.id == id }?.let { token ->
            EditTokenDialog(
                token = token,
                projects = projects,
                onUpdateValue = onUpdateTokenValue,
                onSetAutoDisable = onSetTokenAutoDisable,
                onSetAllowedProjects = onSetTokenAllowedProjects,
                onDismiss = { editingTokenId = null },
            )
        }
    }

    deletingTokenId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingTokenId = null },
            title = { Text("Delete token?") },
            text = { Text("The encrypted value is removed from this device. Anything that still needs it will fail until you add a new one.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveToken(id)
                    deletingTokenId = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingTokenId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddTokenDialog(
    preset: TokenPreset,
    onAdd: (presetId: String, name: String, envVar: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable("token-name-${preset.id}") { mutableStateOf("") }
    var envVar by rememberSaveable("token-env-${preset.id}") { mutableStateOf(preset.envVars.firstOrNull() ?: "") }
    var value by rememberSaveable("token-value-${preset.id}") { mutableStateOf("") }
    val envEditable = preset.id == TokenPresets.CUSTOM_ID
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${preset.title} token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preset.hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. GitHub main)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = envVar,
                    onValueChange = { envVar = it.uppercase() },
                    label = { Text("Environment variable name") },
                    singleLine = true,
                    enabled = envEditable,
                    supportingText = { Text(if (envEditable) "Uppercase A-Z, digits, underscore" else "Fixed by the preset") },
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Token value") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(
                    "Stored encrypted with the Android keystore. Never shown again in full; never sent to any model.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(preset.id, name, envVar, value)
                    onDismiss()
                },
                enabled = value.isNotBlank() && (envEditable.not() || TokenPresets.isValidEnvVarName(envVar.trim())),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditTokenDialog(
    token: StoredToken,
    projects: List<Project>,
    onUpdateValue: (id: String, value: String) -> Unit,
    onSetAutoDisable: (id: String, on: Boolean) -> Unit,
    onSetAllowedProjects: (id: String, projects: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var newValue by rememberSaveable("token-edit-${token.id}") { mutableStateOf("") }
    var autoDisable by rememberSaveable("token-ad-${token.id}") { mutableStateOf(token.autoDisableAfterTask) }
    // Local copy of the allow list; empty = all projects.
    var selected by rememberSaveable("token-proj-${token.id}") {
        mutableStateOf(token.allowedProjects.toTypedArray())
    }
    val selectedSet = selected.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${token.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${token.envVar} = •••••••• (hidden)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newValue,
                    onValueChange = { newValue = it },
                    label = { Text("Replace value (paste a fresh token)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("Replacing the value clears the rotation badge.") },
                )
                Button(
                    onClick = { onUpdateValue(token.id, newValue) },
                    enabled = newValue.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Replace value") }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-disable after a task", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "The token switches itself off when the task that used it finishes (never deleted).",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoDisable, onCheckedChange = { next ->
                        autoDisable = next
                        onSetAutoDisable(token.id, next)
                    })
                }
                HorizontalDivider()
                Text("Available to projects", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (selectedSet.isEmpty()) "All projects (no restriction)" else "Restricted to the selected projects",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                projects.forEach { project ->
                    val checked = selectedSet.contains(project.id)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (checked) selected.filterNot { it == project.id }.toTypedArray()
                            else selected + project.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null)
                        Text(project.name, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { onSetAllowedProjects(token.id, selectedSet) }) { Text("Save project list") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
