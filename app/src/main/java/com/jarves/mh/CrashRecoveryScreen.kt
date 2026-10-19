package com.jarves.mh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.ui.theme.PocketOrange

/**
 * The startup crash-recovery UI (v1.0.31). Shown by MainActivity BEFORE the
 * main UI whenever the newest crash report is newer than the last successful
 * open — the situation v1.0.30 left the user in: the app crashed on every
 * launch, the report sat on disk, and no screen could ever reach it.
 *
 * Two variants driven by [CrashRecovery.decide]:
 *  - [fullScreen] = false → a lightweight dialog over the normal startup.
 *  - [fullScreen] = true  → the full-screen gate (crash loop: 2+ startup
 *    crashes in a row without a successful open).
 *
 * Deliberately static (no animations): this renders on a crash path where
 * the next frame is never guaranteed. Every action is best-effort and
 * cannot itself throw into the UI.
 */
@Composable
fun CrashRecoveryGate(
    report: String?,
    startupCrashes: Int,
    fullScreen: Boolean,
    onContinue: () -> Unit,
    onResetAppData: () -> Unit,
) {
    val context = LocalContext.current
    var showResetConfirm by remember { mutableStateOf(false) }
    var resetArmed by remember { mutableStateOf(false) }

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(PocketOrange.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = null,
                        tint = PocketOrange,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Startup problem detected",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                buildString {
                    append("Mobile Harness crashed the last time it opened (v")
                    append(BuildConfig.VERSION_NAME)
                    append("). The crash was saved on this device, so it can be copied out and reported even without logcat.")
                    if (startupCrashes >= CrashRecovery.FULL_SCREEN_THRESHOLD) {
                        append(" This has now happened ")
                        append(startupCrashes)
                        append(" times in a row.")
                    }
                },
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CrashSummaryBox(report)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Mobile Harness crash report", report ?: "No report"))
                            Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !report.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.7f)),
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp), tint = PocketOrange)
                    Spacer(Modifier.width(7.dp))
                    Text("Copy report", color = PocketOrange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(9.dp))
                OutlinedButton(
                    onClick = {
                        if (resetArmed) showResetConfirm = true else resetArmed = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(
                        1.dp,
                        (if (resetArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    ),
                ) {
                    Icon(Icons.Default.DeleteForever, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (resetArmed) "Confirm reset" else "Reset app data",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }
            Text(
                if (resetArmed) {
                    "Tap \"Confirm reset\", then \"Erase and reopen\" — projects, tasks, tokens and the downloaded runtime will be erased. The crash report is kept so it can still be copied."
                } else {
                    "\"Reset app data\" erases projects, tasks, tokens and the downloaded runtime, then reopens the app fresh. Use it only if the app cannot open at all."
                },
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(7.dp))
                Text(
                    "Continue anyway",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }

    if (fullScreen) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp)) { content() }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onContinue,
            title = { Text("Mobile Harness hit a crash", fontWeight = FontWeight.Bold) },
            text = { content() },
            confirmButton = {},
            dismissButton = {},
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset all app data?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Projects, chats, tasks, tokens and the downloaded Linux runtime will be permanently erased, and the app will reopen fresh. This cannot be undone. The crash report is kept so it can still be copied.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showResetConfirm = false; onResetAppData() }) {
                    Text("Erase and reopen", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun CrashSummaryBox(report: String?) {
    val text = report
        ?.lineSequence()
        ?.take(14)
        ?.joinToString("\n")
        ?.ifBlank { null }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(10.dp)) {
            if (text == null) {
                Text(
                    "The report could not be read this time — it will still be captured on the next crash.",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 14,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
