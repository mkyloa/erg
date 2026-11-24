package org.jellyfin.androidtv.ui.composable

import android.app.ActivityManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.content.getSystemService
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.BlurHashDecoder
import org.koin.compose.koinInject
import kotlin.math.round

/**
 * Loads an image with Coil, drawn directly by Compose. A [blurHash] is shown while the image loads
 * and [placeholder] when there is no [url] or it fails.
 *
 * Erg: this used to wrap the view-based AsyncImageView in an AndroidView. Every card on home is a
 * ComposeView, and the nested Android view made each card re-lay-out and redraw on every frame
 * while the rows scrolled, which made scrolling stutter on TV hardware.
 */
@Composable
fun AsyncImage(
	modifier: Modifier = Modifier,
	url: String? = null,
	blurHash: String? = null,
	placeholder: Drawable? = null,
	aspectRatio: Float = 1f,
	blurHashResolution: Int = 32,
	scaleType: ImageView.ScaleType? = null,
) {
	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()

	val placeholderPainter = remember(placeholder) { placeholder?.asImage()?.asPainter(context) }
	val blurHashPainter by produceState<Painter?>(null, url, blurHash, aspectRatio, blurHashResolution) {
		value = null
		if (url == null || blurHash == null || aspectRatio <= 0f) return@produceState
		if (context.getSystemService<ActivityManager>()?.isLowRamDevice == true) return@produceState
		value = withContext(Dispatchers.Default) {
			BlurHashDecoder.decode(
				blurHash,
				if (aspectRatio > 1) round(blurHashResolution * aspectRatio).toInt() else blurHashResolution,
				if (aspectRatio >= 1) blurHashResolution else round(blurHashResolution / aspectRatio).toInt(),
			)?.let { BitmapPainter(it.asImageBitmap()) }
		}
	}

	val request = remember(url, context) {
		ImageRequest.Builder(context)
			.data(url)
			.crossfade(CROSSFADE_MS)
			.build()
	}

	val (contentScale, alignment) = scaleType.toCompose()
	coil3.compose.AsyncImage(
		model = request,
		contentDescription = null,
		imageLoader = imageLoader,
		placeholder = blurHashPainter ?: placeholderPainter,
		error = placeholderPainter,
		fallback = placeholderPainter,
		contentScale = contentScale,
		alignment = alignment,
		modifier = modifier,
	)
}

private const val CROSSFADE_MS = 100

private fun ImageView.ScaleType?.toCompose(): Pair<ContentScale, Alignment> = when (this) {
	ImageView.ScaleType.CENTER_CROP -> ContentScale.Crop to Alignment.Center
	ImageView.ScaleType.FIT_XY -> ContentScale.FillBounds to Alignment.Center
	ImageView.ScaleType.FIT_START -> ContentScale.Fit to Alignment.CenterStart
	ImageView.ScaleType.FIT_END -> ContentScale.Fit to Alignment.CenterEnd
	ImageView.ScaleType.CENTER_INSIDE -> ContentScale.Inside to Alignment.Center
	ImageView.ScaleType.CENTER -> ContentScale.None to Alignment.Center
	else -> ContentScale.Fit to Alignment.Center
}

@Composable
fun blurHashPainter(
	blurHash: String,
	size: IntSize,
	punch: Float = 1f,
): Painter = remember(blurHash, size, punch) {
	val bitmap = BlurHashDecoder.decode(
		blurHash = blurHash,
		width = size.width,
		height = size.height,
		punch = punch,
	)

	BitmapPainter(requireNotNull(bitmap).asImageBitmap())
}
