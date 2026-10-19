package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.ui.theme.PocketOrange

/**
 * Shared building blocks of the agent configuration cards (v1.0.30): the
 * Antigravity card's account row / model tile / segmented effort capsule /
 * test button / footer extracted so the Z.ai tab can mirror the SAME visual
 * system with ONE implementation — no copy-paste forks. The visual parameters
 * are byte-identical to the originals; the Antigravity card now renders
 * through these pieces too.
 */

/** Account row: colored badge + title (email/name) + status subtitle + optional Disconnect text button. */
@Composable
internal fun AgentAccountRow(
    badge: String,
    badgeColor: Color,
    title: String,
    subtitle: String,
    subtitleColor: Color,
    onDisconnect: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(badgeColor.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(badge, color = badgeColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, fontSize = 11.sp, color = subtitleColor)
        }
        if (onDisconnect != null) {
            Text(
                "Disconnect",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onDisconnect() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** Section label with the orange "Sync" action (model list / site state refresh). */
@Composable
internal fun AgentSyncLabel(label: String, loading: Boolean, onSync: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(enabled = !loading) { onSync() }
                .padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.4.dp)
                Spacer(Modifier.width(4.dp))
            }
            Text("Sync", fontSize = 11.sp, color = PocketOrange, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The model dropdown card: leading icon box, display name, model id/slug in
 * small mono text, trailing chevron. [badge] draws the icon content.
 */
@Composable
internal fun AgentModelTile(
    displayName: String,
    monoSub: String,
    badge: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(PocketOrange.copy(alpha = 0.12f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) { badge() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    monoSub,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Choose model",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Segmented capsule (the "Reasoning Depth" control) — generic over options. */
@Composable
internal fun AgentSegmentedCapsule(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSelected) PocketOrange else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(option) },
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() },
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF241107) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Full-width outlined "Test connection" button with the orange system. */
@Composable
internal fun AgentTestConnectionButton(
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    label: String = "Test connection",
    testingLabel: String = "Testing connection…",
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.7f)),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = PocketOrange)
            Spacer(Modifier.width(8.dp))
        } else {
            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp), tint = PocketOrange)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (loading) testingLabel else label, color = PocketOrange, fontWeight = FontWeight.SemiBold)
    }
}

/** Centered footer note lines (10sp, muted). */
@Composable
internal fun AgentFooterNote(lines: List<String>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, line ->
            Text(
                line,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth(),
            )
            if (index != lines.lastIndex) Spacer(Modifier.height(2.dp))
        }
    }
}
