package org.jellyfin.androidtv.ui.composable

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Loading placeholders ("skeletons").
 *
 * All skeletons inside one [SkeletonGroup] share a single animation clock, so a screen full of
 * placeholders costs one infinite transition rather than one per box. That matters on low-end
 * TV hardware. Outside a group, each placeholder runs its own.
 */

private val LocalShimmerProgress = compositionLocalOf<State<Float>?> { null }

private val SkeletonBase = Color.White.copy(alpha = 0.06f)
private val SkeletonHighlight = Color.White.copy(alpha = 0.14f)

@Composable
private fun rememberShimmerProgress(): State<Float> {
	val transition = rememberInfiniteTransition(label = "skeleton")
	return transition.animateFloat(
		initialValue = 0f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(durationMillis = 1300, easing = LinearEasing), RepeatMode.Restart),
		label = "skeleton-progress",
	)
}

/** Shares one shimmer clock between all skeletons in [content]. */
@Composable
fun SkeletonGroup(content: @Composable () -> Unit) {
	CompositionLocalProvider(LocalShimmerProgress provides rememberShimmerProgress(), content = content)
}

/** Paints an animated shimmer, clipped to [shape]. The animation is read in the draw phase only. */
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
	val progress = LocalShimmerProgress.current ?: rememberShimmerProgress()
	clip(shape).drawBehind {
		// A diagonal highlight band sweeping left to right across the box
		val width = size.width.coerceAtLeast(1f)
		val x = -width + width * 2.5f * progress.value
		drawRect(SkeletonBase)
		drawRect(
			Brush.linearGradient(
				colors = listOf(Color.Transparent, SkeletonHighlight, Color.Transparent),
				start = Offset(x, 0f),
				end = Offset(x + width, size.height * 0.4f),
			)
		)
	}
}

/** A single placeholder block. */
@Composable
fun SkeletonBox(
	modifier: Modifier = Modifier,
	shape: Shape = RoundedCornerShape(8.dp),
) = Box(modifier.shimmer(shape))

/** A placeholder line of text. */
@Composable
fun SkeletonLine(width: Dp, height: Dp = 14.dp, modifier: Modifier = Modifier) =
	SkeletonBox(modifier.size(width, height), RoundedCornerShape(50))

/** A titled row of poster placeholders, matching the app's poster rows. */
@Composable
fun SkeletonPosterRow(
	modifier: Modifier = Modifier,
	cardWidth: Dp = 132.dp,
	cardHeight: Dp = 198.dp,
	count: Int = 8,
	showTitle: Boolean = true,
) {
	Column(modifier) {
		if (showTitle) SkeletonLine(width = 180.dp, height = 18.dp, modifier = Modifier.padding(bottom = 12.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
			repeat(count) { SkeletonBox(Modifier.size(cardWidth, cardHeight)) }
		}
	}
}

/** Placeholder for a hero area: backdrop block with title, meta and overview lines. */
@Composable
fun SkeletonHero(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
	Box(modifier.shimmer(shape)) {
		Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Box(Modifier.size(360.dp, 32.dp).background(SkeletonHighlight, RoundedCornerShape(50)))
			Box(Modifier.size(220.dp, 16.dp).background(SkeletonHighlight, RoundedCornerShape(50)))
			Box(Modifier.height(8.dp))
			repeat(3) { i ->
				Box(Modifier.width(if (i == 2) 300.dp else 520.dp).height(14.dp).background(SkeletonHighlight, RoundedCornerShape(50)))
			}
			Box(Modifier.height(8.dp))
			Box(Modifier.size(110.dp, 40.dp).background(SkeletonHighlight, RoundedCornerShape(50)))
		}
	}
}

