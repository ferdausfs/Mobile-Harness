package com.jarves.mh.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small motion helpers shared by the Z.ai cards (v1.0.31 polish pass).
 *
 * Rule: motion never sits on the startup path and always respects the
 * system animator scale — a user with "remove animations" enabled gets the
 * same information statically.
 */

/**
 * True when the system's animator duration scale is 0 (accessibility
 * "remove animations"). Callers must then skip infinite/transition
 * animations and render the resting state instead.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * A small breathing dot (the live "phase indicator"): silently pulses between
 * two alphas. Under reduced motion it renders as a static dot.
 */
fun Modifier.pocketPulse(
    color: Color,
    size: Dp = 7.dp,
    reducedMotion: Boolean = false,
): Modifier = composed {
    if (reducedMotion) {
        this.background(color.copy(alpha = 0.9f), CircleShape).size(size)
    } else {
        val transition = rememberInfiniteTransition(label = "pocket-pulse")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pocket-pulse-alpha",
        )
        this
            .background(color.copy(alpha = alpha), CircleShape)
            .size(size)
    }
}
