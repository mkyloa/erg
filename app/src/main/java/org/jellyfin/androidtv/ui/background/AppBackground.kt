package org.jellyfin.androidtv.ui.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.composable.modifier.getBackdropFadingColor
import org.koin.compose.koinInject
import timber.log.Timber

@Composable
private fun AppThemeBackground() {
	val context = LocalContext.current
	val themeBackground by remember { derivedStateOf { loadThemeBackground(context) } }

	when (val bg = themeBackground) {
		is BackgroundResult.Bitmap -> {
			Image(
				bitmap = bg.imageBitmap,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize()
			)
		}
		is BackgroundResult.Color -> {
			Box(modifier = Modifier.fillMaxSize().background(bg.color))
		}
		BackgroundResult.Fallback -> {
			Box(modifier = Modifier.fillMaxSize().background(Color.Black))
		}
	}
}

private sealed class BackgroundResult {
	data class Bitmap(val imageBitmap: ImageBitmap) : BackgroundResult()
	data class Color(val color: androidx.compose.ui.graphics.Color) : BackgroundResult()
	object Fallback : BackgroundResult()
}

private fun loadThemeBackground(context: Context): BackgroundResult {
	return try {
		val attributes = context.theme.obtainStyledAttributes(intArrayOf(R.attr.defaultBackground))
		val drawable = attributes.getDrawable(0)
		attributes.recycle()

		when (drawable) {
			is ColorDrawable -> BackgroundResult.Color(androidx.compose.ui.graphics.Color(drawable.color))
			null -> BackgroundResult.Fallback
			else -> {
				val bitmap = createBitmap(240, 135, Bitmap.Config.RGB_565)
				val canvas = Canvas(bitmap)
				drawable.setBounds(0, 0, 240, 135)
				drawable.draw(canvas)
				BackgroundResult.Bitmap(bitmap.asImageBitmap())
			}
		}
	} catch (e: Exception) {
		Timber.e(e, "Error loading theme background")
		BackgroundResult.Fallback
	}
}

private suspend fun extractColorsFromBitmap(imageBitmap: ImageBitmap): ExtractedColors {
	return withContext(Dispatchers.Default) {
		try {
			val androidBitmap = createBitmap(imageBitmap.width, imageBitmap.height)
			val buffer = IntArray(imageBitmap.width * imageBitmap.height)
			imageBitmap.readPixels(buffer)
			androidBitmap.setPixels(buffer, 0, imageBitmap.width, 0, 0, imageBitmap.width, imageBitmap.height)

			val scaledBitmap = if (androidBitmap.width > 200 || androidBitmap.height > 200)
				androidBitmap.scale(200, 200, false).also { androidBitmap.recycle() }
			else androidBitmap

			val palette = Palette.from(scaledBitmap).generate()
			scaledBitmap.recycle()

			val vibrant = palette.vibrantSwatch
			val darkVibrant = palette.darkVibrantSwatch
			val lightVibrant = palette.lightVibrantSwatch
			val muted = palette.mutedSwatch
			val darkMuted = palette.darkMutedSwatch

			fun Palette.Swatch?.isCoolColor(): Boolean {
				if (this == null) return false
				val r = (rgb shr 16) and 0xFF
				val g = (rgb shr 8) and 0xFF
				val b = rgb and 0xFF
				return b > r && (b + g) > (r * 1.5f)
			}

			fun toColor(swatch: Palette.Swatch?, alpha: Float): Color {
				return swatch?.rgb?.let { Color(it).copy(alpha = alpha) } ?: Color.Transparent
			}

			val primaryColor = toColor(darkVibrant ?: darkMuted, 0.4f)
			val secondaryColor = when {
				vibrant != null && vibrant.isCoolColor() -> toColor(vibrant, 0.4f)
				muted != null && muted.isCoolColor() -> toColor(muted, 0.4f)
				vibrant != null -> toColor(vibrant, 0.4f)
				muted != null -> toColor(muted, 0.4f)
				else -> Color.Transparent
			}
			val tertiaryColor = toColor(vibrant ?: lightVibrant, 0.35f)

			ExtractedColors(primaryColor, secondaryColor, tertiaryColor)
		} catch (e: Exception) {
			Timber.e(e, "Error extracting palette colors")
			ExtractedColors(Color.Transparent, Color.Transparent, Color.Transparent)
		}
	}
}

data class ExtractedColors(
	val primary: Color,
	val secondary: Color,
	val tertiary: Color
)

@Composable
fun AppBackground() {
	val backgroundService: BackgroundService = koinInject()
	val blockAllBackgrounds by backgroundService.blockAllBackgrounds.collectAsState()

	if (blockAllBackgrounds) {
		AppThemeBackground()
		return
	}

	val currentBackground by backgroundService.currentBackground.collectAsState()
	val enabled by backgroundService.enabled.collectAsState()
	val backdropFadingIntensity by backgroundService.backdropFadingIntensity.collectAsState()
	val backdropDynamicColors by backgroundService.backdropDynamicColors.collectAsState()

	var primaryColor by remember { mutableStateOf<Color?>(null) }
	var secondaryColor by remember { mutableStateOf<Color?>(null) }
	var tertiaryColor by remember { mutableStateOf<Color?>(null) }
	var showImage by remember { mutableStateOf(false) }
	var lastValidBackground by remember { mutableStateOf<Any?>(null) }

	LaunchedEffect(currentBackground) {
		if (currentBackground != null) {
			lastValidBackground = currentBackground
		}
	}

	val displayBackground = currentBackground ?: lastValidBackground

	val animatedPrimaryColor by animateColorAsState(
		targetValue = primaryColor ?: Color.Transparent,
		animationSpec = tween(durationMillis = 700, easing = LinearEasing),
		label = "primary_color"
	)
	val animatedSecondaryColor by animateColorAsState(
		targetValue = secondaryColor ?: Color.Transparent,
		animationSpec = tween(durationMillis = 700, easing = LinearEasing),
		label = "secondary_color"
	)
	val animatedTertiaryColor by animateColorAsState(
		targetValue = tertiaryColor ?: Color.Transparent,
		animationSpec = tween(durationMillis = 700, easing = LinearEasing),
		label = "tertiary_color"
	)

	LaunchedEffect(currentBackground, backdropDynamicColors) {
		if (currentBackground != null && backdropDynamicColors) {
			delay(600)
			val background = currentBackground
			if (background != null) {
				try {
					val extracted = extractColorsFromBitmap(background)
					primaryColor = extracted.primary
					secondaryColor = extracted.secondary
					tertiaryColor = extracted.tertiary
					showImage = true
				} catch (e: Exception) {
					Timber.e(e, "Error extracting colors")
					primaryColor = null
					secondaryColor = null
					tertiaryColor = null
					showImage = false
				}
			} else {
				showImage = false
			}
		} else {
			if (!backdropDynamicColors) {
				primaryColor = null
				secondaryColor = null
				tertiaryColor = null
			}
			showImage = !backdropDynamicColors
		}
	}

	if (!enabled) {
		AppThemeBackground()
		return
	}

	val context = LocalContext.current
	val fallbackBackgroundColor = remember {
		val typedArray = context.theme.obtainStyledAttributes(intArrayOf(R.attr.background_filter))
		val color = Color(typedArray.getColor(0, 0x000000))
		typedArray.recycle()
		color
	}

	val solidFadingColor = getBackdropFadingColor()
	val fadingColor = if (backdropDynamicColors) animatedPrimaryColor else solidFadingColor
	val primaryGradientColor = if (backdropDynamicColors) animatedPrimaryColor else Color.Transparent
	val secondaryGradientColor = if (backdropDynamicColors) animatedSecondaryColor else Color.Transparent
	val tertiaryGradientColor = if (backdropDynamicColors) animatedTertiaryColor else Color.Transparent

	// Erg: the color wash is painted into a tiny bitmap and stretched (see drawColorWash), and the
	// faded image sits in its own cached layer. Drawing a full-screen fill, three full-screen radial
	// gradients and the image layer on every frame cost ~25ms of GPU time per frame on TV hardware,
	// and the colors animate after every selection change on browse screens.
	val washBitmap = remember { ImageBitmap(WASH_WIDTH, WASH_HEIGHT) }
	Box(modifier = Modifier.fillMaxSize()) {
		if (displayBackground != null) {
			Box(modifier = Modifier.fillMaxSize()) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.drawBehind {
							val gradients = backdropFadingIntensity > 0
							drawColorWash(
								bitmap = washBitmap,
								fill = fadingColor,
								topLeft = if (gradients) secondaryGradientColor else Color.Transparent,
								bottomRight = if (gradients) primaryGradientColor else Color.Transparent,
								topRight = if (gradients) tertiaryGradientColor else Color.Transparent,
							)
						}
				)

				Box(modifier = Modifier.fillMaxSize()) {
					if (showImage && displayBackground != null) {
						Box(
							modifier = Modifier
								.width(650.dp)
								.aspectRatio(16f / 10f)
								.align(Alignment.TopEnd)
						) {
							AnimatedContent(
								targetState = showImage,
								transitionSpec = {
									fadeIn(
										animationSpec = tween(durationMillis = 600, delayMillis = 100, easing = LinearEasing)
									) togetherWith fadeOut(
										animationSpec = tween(durationMillis = 600, easing = LinearEasing)
									)
								},
								label = "image_transition"
							) { _ ->
								if (displayBackground is ImageBitmap) {
									Image(
										bitmap = displayBackground,
										contentDescription = null,
										contentScale = ContentScale.Crop,
										modifier = Modifier
											.fillMaxSize()
											// Own cached layer: the fade below is only redrawn when the image changes
											.graphicsLayer {
												alpha = 0.95f
												compositingStrategy = CompositingStrategy.Offscreen
											}
											.drawWithContent {
												drawContent()
												if (backdropFadingIntensity > 0) { // More is less & less is more here
													val horizontalFadeSize = (1.7f - backdropFadingIntensity) * 62f
													val verticalFadeSize = (1.5f - backdropFadingIntensity) * 320f

													drawRect(
														brush = Brush.horizontalGradient(
															colors = listOf(Color.Transparent, Color.Black),
															startX = 0f,
															endX = horizontalFadeSize.dp.toPx(),
														),
														blendMode = BlendMode.DstIn,
													)
													drawRect(
														brush = Brush.verticalGradient(
															colors = listOf(Color.Black, Color.Transparent),
															startY = 0f,
															endY = verticalFadeSize.dp.toPx(),
														),
														blendMode = BlendMode.DstIn,
													)
												}
											}
									)
								}
							}
						}
					}
				}
			}
		} else {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(fadingColor)
					.alpha(0.95f)
			)
		}
	}
}

private const val WASH_WIDTH = 96
private const val WASH_HEIGHT = 54

/**
 * Paints the backdrop color wash (a fill plus soft radial glows from three corners) into a tiny
 * bitmap on the CPU and stretches it over the screen. The glows are smooth, so bilinear scaling
 * looks the same as drawing them full size, at a fraction of the GPU cost.
 */
private fun DrawScope.drawColorWash(bitmap: ImageBitmap, fill: Color, topLeft: Color, bottomRight: Color, topRight: Color) {
	if (topLeft == Color.Transparent && bottomRight == Color.Transparent && topRight == Color.Transparent) {
		drawRect(fill)
		return
	}

	val w = bitmap.width.toFloat()
	val h = bitmap.height.toFloat()
	val radius = w * 0.4f
	val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
	val paint = Paint()
	paint.blendMode = BlendMode.Src
	paint.color = fill
	canvas.drawRect(0f, 0f, w, h, paint)
	paint.blendMode = BlendMode.SrcOver
	for ((color, center) in listOf(topLeft to Offset(0f, 0f), bottomRight to Offset(w, h), topRight to Offset(w, 0f))) {
		if (color == Color.Transparent) continue
		paint.color = Color.Black
		paint.shader = RadialGradientShader(center, radius, listOf(color, Color.Transparent))
		canvas.drawRect(0f, 0f, w, h, paint)
	}

	drawImage(
		image = bitmap,
		dstSize = IntSize(size.width.toInt(), size.height.toInt()),
		filterQuality = FilterQuality.Low,
	)
}
