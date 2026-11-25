package org.jellyfin.androidtv.ui.playback.mpv

import android.content.Context
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.preference.constant.BufferLength
import org.jellyfin.androidtv.preference.constant.VideoPlayerEngine
import org.jellyfin.androidtv.util.profile.isBitstreamAudioEnabled
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder

object MpvSupport {
	/** libmpv for Android requires Android 8.0 (API 26). */
	@JvmStatic
	val isSupported get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O

	@JvmStatic
	fun isEnabled(userPreferences: UserPreferences) =
		isSupported && userPreferences[UserPreferences.videoPlayerEngine] == VideoPlayerEngine.MPV

	/** Builds mpv options from the user's playback preferences and the current session. */
	@JvmStatic
	fun createOptions(context: Context, userPreferences: UserPreferences, api: ApiClient): MpvPlayer.Options {
		// mpv names for the formats the receiver should decode itself
		val spdif = buildList {
			if (userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.AC3)) add("ac3")
			if (userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.EAC3)) add("eac3")
			if (userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.DTS)) addAll(listOf("dts", "dts-hd"))
			if (userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.TRUEHD)) add("truehd")
		}.joinToString(",")

		val authorization = api.accessToken?.let { token ->
			AuthorizationHeaderBuilder.buildHeader(
				api.clientInfo.name,
				api.clientInfo.version,
				api.deviceInfo.id,
				api.deviceInfo.name,
				token,
			)
		}

		val cacheMegabytes = when (userPreferences[UserPreferences.bufferLength]) {
			BufferLength.LARGE -> 192
			BufferLength.EXTRA_LARGE -> 320
			else -> 96
		}

		// The ExoPlayer setting is an absolute size (24 by default); mpv scales relative to its default
		val subtitleScale = (userPreferences[UserPreferences.subtitlesTextSize] / 24f).toDouble().coerceIn(0.5, 3.0)

		return MpvPlayer.Options(
			authorizationHeader = authorization,
			serverHost = runCatching { android.net.Uri.parse(api.baseUrl).host }.getOrNull(),
			userAgent = "${api.clientInfo.name}/${api.clientInfo.version}",
			audioSpdif = spdif,
			cacheMegabytes = cacheMegabytes,
			subtitleScale = subtitleScale,
			debugLogging = userPreferences[UserPreferences.debuggingEnabled],
		)
	}
}
