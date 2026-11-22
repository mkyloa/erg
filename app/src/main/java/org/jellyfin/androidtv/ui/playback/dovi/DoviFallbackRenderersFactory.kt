package org.jellyfin.androidtv.ui.playback.dovi

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import timber.log.Timber

/**
 * Plays Dolby Vision profile 7 on devices without a decoder for it.
 *
 * Profile 7 (UHD Blu-ray) carries an HDR10-compatible HEVC base layer plus an enhancement layer.
 * Media3 has no fallback for it, so such files fail or are transcoded. When [enabled], this
 * presents profile 7 streams to the decoder as plain HEVC: the device's regular HEVC decoder plays
 * the HDR10 base layer and ignores the enhancement layer. Other Dolby Vision profiles are untouched.
 */
@OptIn(UnstableApi::class)
class DoviFallbackRenderersFactory(
	context: Context,
	private val enabled: Boolean,
) : DefaultRenderersFactory(context) {
	override fun buildVideoRenderers(
		context: Context,
		extensionRendererMode: Int,
		mediaCodecSelector: MediaCodecSelector,
		enableDecoderFallback: Boolean,
		eventHandler: Handler,
		eventListener: VideoRendererEventListener,
		allowedVideoJoiningTimeMs: Long,
		out: ArrayList<Renderer>,
	) {
		super.buildVideoRenderers(
			context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
			eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
		)
		if (!enabled) return

		// Swap the platform video renderer for one that knows the fallback; keep extension renderers
		val index = out.indexOfFirst { it.javaClass == MediaCodecVideoRenderer::class.java }
		if (index == -1) return
		out[index] = DoviFallbackVideoRenderer(
			context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback,
			eventHandler, eventListener,
		)
	}
}

@OptIn(UnstableApi::class)
private class DoviFallbackVideoRenderer(
	context: Context,
	mediaCodecSelector: MediaCodecSelector,
	allowedJoiningTimeMs: Long,
	enableDecoderFallback: Boolean,
	eventHandler: Handler,
	eventListener: VideoRendererEventListener,
) : MediaCodecVideoRenderer(
	context,
	MediaCodecAdapter.Factory.getDefault(context),
	mediaCodecSelector,
	allowedJoiningTimeMs,
	enableDecoderFallback,
	eventHandler,
	eventListener,
	MAX_DROPPED_FRAMES_TO_NOTIFY,
) {
	override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
		super.supportsFormat(mediaCodecSelector, format.asBaseLayer())

	override fun getDecoderInfos(
		mediaCodecSelector: MediaCodecSelector,
		format: Format,
		requiresSecureDecoder: Boolean,
	): List<MediaCodecInfo> {
		val target = format.asBaseLayer()
		if (target !== format) Timber.i("Playing Dolby Vision profile 7 base layer with an HEVC decoder")
		return super.getDecoderInfos(mediaCodecSelector, target, requiresSecureDecoder)
	}

	override fun getMediaFormat(
		format: Format,
		codecMimeType: String,
		codecMaxValues: CodecMaxValues,
		codecOperatingRate: Float,
		deviceNeedsNoPostProcessWorkaround: Boolean,
		tunnelingAudioSessionId: Int,
	): MediaFormat = super.getMediaFormat(
		// Without this, Media3 would set a Dolby Vision profile on the HEVC decoder
		if (codecMimeType == MimeTypes.VIDEO_H265) format.asBaseLayer() else format,
		codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId,
	)

	private companion object {
		const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50
	}
}

/** True for Dolby Vision profile 7 (dual layer, HDR10-compatible base layer). */
@OptIn(UnstableApi::class)
internal fun Format.isDolbyVisionProfile7(): Boolean {
	if (sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return false
	codecs?.lowercase()?.let { if (it.startsWith("dvhe.07") || it.startsWith("dvh1.07")) return true }
	return runCatching { MediaCodecUtil.getCodecProfileAndLevel(this)?.first == CodecProfileLevel.DolbyVisionProfileDvheDtb }
		.getOrDefault(false)
}

/** The same stream described as its HEVC base layer, or this format when it isn't profile 7. */
@OptIn(UnstableApi::class)
internal fun Format.asBaseLayer(): Format =
	if (isDolbyVisionProfile7()) buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build()
	else this
