package org.jellyfin.androidtv.util.sdk

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.jellyfin.sdk.model.api.BaseItemDto

object TrailerUtils {
	private const val YOUTUBE_HOST = "youtube.com"
	private const val YOUTUBE_ID_PARAMETER = "v"
	private const val YOUTUBE_URL = "https://youtube.com/watch?v="
	private const val YOUTUBE_ID_LENGTH = 11
	private const val FRAMEWORK_STUB_PACKAGE = "com.android.tv.frameworkpackagestubs"

	@JvmStatic
	fun getExternalTrailerIntent(src: String): Intent {
		val uri = src.toUri()

		// Recreate YouTube urls
		if (uri.host?.endsWith(YOUTUBE_HOST) == true) {
			val id = uri.getQueryParameter(YOUTUBE_ID_PARAMETER).orEmpty()
			if (id.length == YOUTUBE_ID_LENGTH) return Intent(Intent.ACTION_VIEW, "$YOUTUBE_URL$id".toUri())
		}

		return Intent(Intent.ACTION_VIEW, uri)
	}

	@JvmStatic
	fun getExternalTrailerIntent(context: Context, item: BaseItemDto): Intent? = item.remoteTrailers.orEmpty()
		.mapNotNull { it.url?.let(::getExternalTrailerIntent) }
		.firstOrNull {
			val component = it.resolveActivity(context.packageManager)

			// Check if there is an activity to handle the intent
			// exclude the FrameworkPackageStubs module, which only displays a message
			// that there is no app to open the intent
			component != null && component.packageName != FRAMEWORK_STUB_PACKAGE
		}

	// Erg: YouTube apps that take watch links, best first. SmartTube is ad-free, so it wins when
	// both are installed; Android TV's YouTube often doesn't claim plain links unless asked directly.
	private val YOUTUBE_PACKAGES = listOf(
		"org.smarttube.stable",
		"org.smarttube.beta",
		"com.teamsmart.videomanager.tv",
		"com.liskovsoft.smarttubetv.beta",
		"com.liskovsoft.smarttubetv",
		"com.google.android.youtube.tv",
		"com.amazon.firetv.youtube",
		"com.google.android.youtube",
	)

	/** Opens a YouTube video by id. Returns false when nothing on this device can play it. */
	fun openYouTube(context: Context, videoId: String): Boolean {
		val uri = "$YOUTUBE_URL$videoId".toUri()
		fun tryStart(intent: Intent) = runCatching {
			context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
		}.isSuccess

		if (YOUTUBE_PACKAGES.any { tryStart(Intent(Intent.ACTION_VIEW, uri).setPackage(it)) }) return true

		val generic = Intent(Intent.ACTION_VIEW, uri)
		val component = generic.resolveActivity(context.packageManager)
		return component != null && component.packageName != FRAMEWORK_STUB_PACKAGE && tryStart(generic)
	}

	@JvmStatic
	fun hasPlayableTrailers(context: Context, item: BaseItemDto): Boolean {
		// Local trailer
		if (item.localTrailerCount != null && item.localTrailerCount!! > 0) return true

		// External trailer
		if (getExternalTrailerIntent(context, item) != null) return true

		// No trailer found
		return false
	}
}
