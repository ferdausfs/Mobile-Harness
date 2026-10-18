package com.jarves.mh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import com.jarves.mh.zweb.ZaiChatPromptBuilder
import com.jarves.mh.zweb.ZaiChatTurnPresenter
import com.jarves.mh.zweb.ZaiPendingApply
import com.jarves.mh.zweb.ZaiWorkspaceApply
import com.jarves.mh.ui.theme.PocketGreen
import com.jarves.mh.ui.theme.PocketOrange

/**
 * Chat-side cards for the Z.ai chat engine (v1.0.25): the terminal notice
 * (sign-in / captcha / timeout / error / stopped), the review-and-apply card
 * for files a Z.ai reply returned, and the chat-engine picker sheet for the
 * workspace chat header. Pure presentation — decisions live in the ViewModel
 * and the pure zweb policy objects.
 */

@Composable
private fun ZaiDiffLineRow(line: DiffLine) {
    val marker = when (line.type) {
        DiffLineType.ADDITION -> "+"
        DiffLineType.DELETION -> "-"
        DiffLineType.CONTEXT -> " "
        DiffLineType.INFO -> "·"
    }
    val background = when (line.type) {
        DiffLineType.ADDITION -> Color(0xFF123226)
        DiffLineType.DELETION -> Color(0xFF3A1D22)
        else -> Color.Transparent
    }
    val foreground = when (line.type) {
        DiffLineType.ADDITION -> Color(0xFF83E6B8)
        DiffLineType.DELETION -> Color(0xFFFFA4A4)
        DiffLineType.INFO -> Color(0xFF8993A4)
        DiffLineType.CONTEXT -> Color(0xFFD5DAE3)
    }
    Text(
        text = "$marker ${line.text}",
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = foreground,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        softWrap = false,
    )
}

@Composable
fun ZaiChatNoticeCard(
    notice: ZaiChatTurnPresenter.TurnNotice,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = when (notice.kind) {
        ZaiChatTurnPresenter.NoticeKind.NEEDS_LOGIN, ZaiChatTurnPresenter.NoticeKind.CAPTCHA -> PocketOrange
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (notice.kind) {
                        ZaiChatTurnPresenter.NoticeKind.NEEDS_LOGIN -> "Sign-in needed"
                        ZaiChatTurnPresenter.NoticeKind.CAPTCHA -> "Verification needed"
                        ZaiChatTurnPresenter.NoticeKind.TIMEOUT -> "Z.ai timed out"
                        ZaiChatTurnPresenter.NoticeKind.ERROR -> "Z.ai error"
                        ZaiChatTurnPresenter.NoticeKind.CANCELED -> "Stopped"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
            Text(
                notice.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notice.showSignIn) {
                    Button(onClick = onSignIn, shape = RoundedCornerShape(10.dp)) {
                        Text("Sign in to Z.ai")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
fun ZaiFileReviewCard(
    pending: ZaiPendingApply,
    autoApply: Boolean,
    onAutoApply: (Boolean) -> Unit,
    onApplyAll: () -> Unit,
    onApplyFile: (String) -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(PocketOrange.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Z", color = PocketOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (pending.files.size == 1) "Z.ai changed 1 file" else "Z.ai changed ${pending.files.size} files",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Text(
                        buildString {
                            append("Review before applying")
                            if (pending.unchanged.isNotEmpty()) {
                                append(" · ${pending.unchanged.size} unchanged")
                            }
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (pending.rejected.isNotEmpty()) {
                Text(
                    "Skipped (not applied): " +
                        pending.rejected.joinToString { "${it.path} — ${it.reason}" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            pending.files.forEach { file ->
                var expanded by rememberSaveable("zai-review-" + file.path) { mutableStateOf(false) }
                val additions = file.diffLines.count { it.type == DiffLineType.ADDITION }
                val deletions = file.diffLines.count { it.type == DiffLineType.DELETION }
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (file.status == ZaiWorkspaceApply.FileStatus.NEW) {
                                Color(0xFF123226)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Text(
                                if (file.status == ZaiWorkspaceApply.FileStatus.NEW) "NEW" else "MOD",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (file.status == ZaiWorkspaceApply.FileStatus.NEW) {
                                    Color(0xFF83E6B8)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            file.path,
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (additions > 0) {
                            Text("+$additions", fontSize = 11.sp, color = Color(0xFF83E6B8))
                            Spacer(Modifier.width(4.dp))
                        }
                        if (deletions > 0) {
                            Text("-$deletions", fontSize = 11.sp, color = Color(0xFFFFA4A4))
                        }
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Hide diff" else "Show diff",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(visible = expanded) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF0E1116),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp),
                            ) {
                                if (file.diffLines.isEmpty()) {
                                    Text(
                                        "No preview available.",
                                        Modifier.padding(8.dp),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                file.diffLines.take(300).forEach { line -> ZaiDiffLineRow(line) }
                                if (file.diffLines.size > 300) {
                                    Text(
                                        "… ${file.diffLines.size - 300} more diff lines (Apply still applies the full file)",
                                        Modifier.padding(8.dp),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = expanded) {
                        OutlinedButton(
                            onClick = { onApplyFile(file.path) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Apply ${file.path.substringAfterLast('/')}")
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Auto apply",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(checked = autoApply, onCheckedChange = onAutoApply)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApplyAll,
                    enabled = pending.files.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (pending.files.size == 1) "Apply" else "Apply all")
                }
                OutlinedButton(onClick = onReject, shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Default.Stop, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Reject", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                "A checkpoint is created before applying — Undo is available in the Changes tab.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Engine picker for the workspace chat header: Z.ai Web or one of the CLI agents. */
@Composable
fun ZaiEnginePickerSheet(
    zaiActive: Boolean,
    activeAgentKind: AgentKind,
    zaiSignedIn: Boolean?,
    zaiCheckingConnection: Boolean,
    zaiBusy: Boolean,
    onPickEngine: (String) -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat engine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineOptionRow(
                    title = ZaiChatPromptBuilder.ZAI_ENGINE_TITLE,
                    subtitle = when {
                        zaiCheckingConnection -> "Checking chat.z.ai…"
                        zaiSignedIn == true -> "Connected with chat.z.ai · web chat engine"
                        zaiSignedIn == false -> "Not signed in — tap to sign in"
                        else -> "Sign-in state unknown — tap to check"
                    },
                    selected = zaiActive,
                    onClick = {
                        onPickEngine(ZaiChatPromptBuilder.ZAI_WEB_ENGINE_ID)
                        if (zaiSignedIn != true) onSignIn()
                        onDismiss()
                    },
                    badge = { Text("Z", color = PocketOrange, fontWeight = FontWeight.Bold, fontSize = 14.sp) },
                    trailing = {
                        if (!zaiActive && zaiSignedIn != true) {
                            TextButton(onClick = {
                                onSignIn()
                            }) { Text("Sign in") }
                        }
                    },
                )
                if (zaiSignedIn != true) {
                    Text(
                        "Sign in to Z.ai once — then send messages from this chat.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AgentKind.entries.forEach { agent ->
                    EngineOptionRow(
                        title = agent.title,
                        subtitle = "CLI agent in the Linux runtime",
                        selected = !zaiActive && activeAgentKind == agent,
                        onClick = {
                            onPickEngine(agent.stableId)
                            onDismiss()
                        },
                        badge = {
                            Text(
                                agent.title.take(1),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        },
                    )
                }
                Text(
                    "CLI agents edit files and run commands themselves. Z.ai answers with text + files you review and apply.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (zaiBusy) {
                    Text(
                        "Z.ai is still working on a task in its tab.",
                        fontSize = 11.sp,
                        color = PocketOrange,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun EngineOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) { badge() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 13.sp)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (selected) Icon(Icons.Default.Check, "Selected", tint = PocketGreen, modifier = Modifier.size(18.dp))
            trailing?.invoke()
        }
    }
}
