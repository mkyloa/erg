package org.jellyfin.androidtv.seerr.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.jellyfin.androidtv.ui.composable.shimmer

/** Remote image that shows a shimmer until it loads, then crossfades in. */
@Composable
fun SeerrImage(
	url: String,
	contentDescription: String?,
	modifier: Modifier = Modifier,
	shape: Shape = RectangleShape,
	crossfadeMillis: Int = 250,
) {
	val context = LocalContext.current
	var loading by remember(url) { mutableStateOf(true) }
	val request = remember(url) {
		ImageRequest.Builder(context).data(url).crossfade(crossfadeMillis).build()
	}

	Box(modifier) {
		if (loading) Box(Modifier.fillMaxSize().shimmer(shape))
		AsyncImage(
			model = request,
			contentDescription = contentDescription,
			contentScale = ContentScale.Crop,
			onSuccess = { loading = false },
			onError = { loading = false },
			modifier = Modifier.fillMaxSize(),
		)
	}
}
