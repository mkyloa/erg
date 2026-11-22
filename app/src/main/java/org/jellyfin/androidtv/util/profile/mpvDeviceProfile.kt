package org.jellyfin.androidtv.util.profile

import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

/** Text subtitle formats mpv renders itself, delivered embedded or as separate files. */
private val mpvTextSubtitles = listOf(
	Codec.Subtitle.ASS,
	Codec.Subtitle.SSA,
	Codec.Subtitle.SRT,
	Codec.Subtitle.SUBRIP,
	Codec.Subtitle.VTT,
	Codec.Subtitle.WEBVTT,
	Codec.Subtitle.TTML,
	"mov_text",
	"smi",
	"microdvd",
	"sub",
)

/** Bitmap subtitle formats mpv renders, which only exist inside the video container. */
private val mpvImageSubtitles = listOf(
	Codec.Subtitle.PGS,
	Codec.Subtitle.PGSSUB,
	Codec.Subtitle.DVDSUB,
	Codec.Subtitle.DVBSUB,
	Codec.Subtitle.IDX,
)

/**
 * Device profile for the mpv engine.
 *
 * Direct play profiles without container or codec lists match everything, so the server sends
 * files as they are and mpv decodes them (in hardware when the device can, otherwise in
 * software). Transcoding remains as a fallback for bitrate limits.
 */
fun createMpvDeviceProfile(maxBitrate: Int) = buildDeviceProfile {
	name = "Erg-mpv"

	maxStaticBitrate = maxBitrate
	maxStreamingBitrate = maxBitrate

	transcodingProfile {
		type = DlnaProfileType.VIDEO
		context = EncodingContext.STREAMING
		container = Codec.Container.TS
		protocol = MediaStreamProtocol.HLS
		videoCodec(Codec.Video.HEVC, Codec.Video.H264)
		audioCodec(Codec.Audio.AAC, Codec.Audio.AC3, Codec.Audio.EAC3, Codec.Audio.MP3)
		copyTimestamps = false
		enableSubtitlesInManifest = true
	}

	transcodingProfile {
		type = DlnaProfileType.AUDIO
		context = EncodingContext.STREAMING
		container = Codec.Container.TS
		protocol = MediaStreamProtocol.HLS
		audioCodec(Codec.Audio.AAC)
	}

	// Any container, any codec
	directPlayProfile { type = DlnaProfileType.VIDEO }
	directPlayProfile { type = DlnaProfileType.AUDIO }

	for (format in mpvTextSubtitles) {
		subtitleProfile(format, SubtitleDeliveryMethod.EMBED)
		subtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL)
	}
	for (format in mpvImageSubtitles) {
		subtitleProfile(format, SubtitleDeliveryMethod.EMBED)
		// Burned in only when the server has to transcode anyway
		subtitleProfile(format, SubtitleDeliveryMethod.ENCODE)
	}
}
