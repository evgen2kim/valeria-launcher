// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** A small rise and fade, reset while unplaced so every opening gets an entrance. */
@Composable
fun Modifier.listEntrance(visible: Boolean): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) progress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        else progress.snapTo(0f)
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 14.dp.toPx()
    }
}
