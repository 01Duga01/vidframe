/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.mozilla.fenix.R

private const val ENTRANCE_MS = 520
private const val RING_TURN_MS = 1400
private const val BREATH_MS = 1800
private const val PERCENT = 100

/**
 * First-run screen while uBlock Origin is checked, downloaded and installed. Honest about what happens and how long it
 * may take; [showSkip] reveals a way to continue without waiting.
 */
@Composable
fun VidframeSetupScreen(state: UboSetupState, showSkip: Boolean, onSkip: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val brandStart = colorResource(R.color.vidframe_brand_start)
    val brandEnd = colorResource(R.color.vidframe_brand_end)
    val colors =
        if (dark) {
            darkColorScheme(primary = brandEnd, background = colorResource(R.color.vidframe_splash_background))
        } else {
            lightColorScheme(primary = brandStart, background = colorResource(R.color.vidframe_splash_background))
        }

    MaterialTheme(colorScheme = colors) {
        // Content rises and fades in once, right after the splash icon has handed over.
        val entrance = remember { Animatable(0f) }
        LaunchedEffect(Unit) { entrance.animateTo(1f, tween(ENTRANCE_MS, easing = FastOutSlowInEasing)) }

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier.widthIn(max = 420.dp)
                        .padding(horizontal = 32.dp)
                        .graphicsLayer {
                            alpha = entrance.value
                            translationY = (1f - entrance.value) * 48.dp.toPx()
                        },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SetupBadge(
                    brush = Brush.linearGradient(listOf(brandStart, brandEnd)),
                    busy = state.phase != UboSetupState.Phase.READY,
                )

                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(R.string.vidframe_setup_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.vidframe_setup_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(32.dp))
                SetupProgress(state)

                Spacer(Modifier.height(12.dp))
                // Animate only phase changes; the percentage inside a phase updates in place.
                AnimatedContent(
                    targetState = state.phase,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically { it / 2 }) togetherWith
                            (fadeOut(tween(160)) + slideOutVertically { -it / 2 })
                    },
                    label = "setup-status",
                ) { phase ->
                    Text(
                        text = statusText(state.copy(phase = phase)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(24.dp))
                AnimatedVisibility(visible = showSkip, enter = fadeIn(tween(400)), exit = fadeOut()) {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.vidframe_setup_skip)) }
                }
            }
        }
    }
}

/** The app mark on its gradient disc, gently breathing, with a rotating gradient arc while work is in progress. */
@Composable
private fun SetupBadge(brush: Brush, busy: Boolean) {
    val transition = rememberInfiniteTransition(label = "setup-badge")
    val angle by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(RING_TURN_MS, easing = LinearEasing)),
            label = "ring",
        )
    val breath by
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath",
        )
    val ringAlpha by animateFloatAsState(if (busy) 1f else 0f, tween(300), label = "ring-alpha")

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(148.dp)) {
        Canvas(modifier = Modifier.size(148.dp).graphicsLayer { alpha = ringAlpha }) {
            rotate(angle) {
                drawArc(
                    brush = brush,
                    startAngle = 0f,
                    sweepAngle = 110f,
                    useCenter = false,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(112.dp)
                    .graphicsLayer {
                        scaleX = breath
                        scaleY = breath
                    }
                    .clip(CircleShape)
                    .background(brush),
        ) {
            Image(
                painter = painterResource(R.drawable.vidframe_ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(112.dp),
            )
        }
    }
}

@Composable
private fun SetupProgress(state: UboSetupState) {
    val modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
    val progress = state.progress
    if (state.phase == UboSetupState.Phase.DOWNLOADING && progress != null) {
        val animated by
            animateFloatAsState(
                targetValue = progress,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
                label = "download-progress",
            )
        LinearProgressIndicator(progress = { animated }, modifier = modifier, drawStopIndicator = {})
    } else if (state.phase == UboSetupState.Phase.READY) {
        LinearProgressIndicator(progress = { 1f }, modifier = modifier, drawStopIndicator = {})
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

@Composable
private fun statusText(state: UboSetupState): String {
    val version = state.version.orEmpty()
    val progress = state.progress
    return when (state.phase) {
        UboSetupState.Phase.CHECKING -> stringResource(R.string.vidframe_setup_checking)
        UboSetupState.Phase.DOWNLOADING ->
            if (progress != null) {
                stringResource(R.string.vidframe_setup_downloading_percent, version, (progress * PERCENT).toInt())
            } else {
                stringResource(R.string.vidframe_setup_downloading, version)
            }
        UboSetupState.Phase.INSTALLING -> stringResource(R.string.vidframe_setup_installing, version)
        UboSetupState.Phase.READY -> stringResource(R.string.vidframe_setup_ready)
    }
}
