package com.familydocs.desktop.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.familydocs.app.ui.ds.FdEase
import kotlinx.coroutines.delay

/** The page change's scopes, so a picture can travel from one page to the next ([shared]). */
@OptIn(ExperimentalSharedTransitionApi::class)
class PageMotion(val shared: SharedTransitionScope, val page: AnimatedVisibilityScope)

val LocalPageMotion = staticCompositionLocalOf<PageMotion?> { null }

/**
 * The same thing on two pages (a person's picture on Home and on their page, an event's picture, a card): while the
 * page changes it moves and grows from one place to the other instead of fading.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.shared(key: String): Modifier {
    val m = LocalPageMotion.current ?: return this
    return with(m.shared) {
        this@shared.sharedElement(rememberSharedContentState(key), m.page,
            boundsTransform = BoundsTransform { _, _ -> tween(460, easing = FdEase) })
    }
}

/** Comes in like a card being dealt: fades up and settles, one after another ([index]). */
@Composable
fun Modifier.appear(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(80L + minOf(index, 14) * 45L)
        a.animateTo(1f, tween(420, easing = FdEase))
    }
    return this.graphicsLayer {
        val v = a.value
        alpha = v
        translationY = (1f - v) * 26.dp.toPx()
        val k = .94f + .06f * v
        scaleX = k; scaleY = k
    }
}
