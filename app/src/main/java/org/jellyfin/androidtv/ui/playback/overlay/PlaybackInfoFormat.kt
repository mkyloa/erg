package org.jellyfin.androidtv.ui.playback.overlay

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** Human-readable names for the playback info panel. */
object PlaybackInfoFormat {
	@JvmStatic
	fun codec(mime: String?): String = when (mime) {
		null -> "?"
		MimeTypes.VIDEO_H264 -> "H.264"
		MimeTypes.VIDEO_H265 -> "HEVC"
		MimeTypes.VIDEO_AV1 -> "AV1"
		MimeTypes.VIDEO_VP9 -> "VP9"
		MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
		MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
		MimeTypes.VIDEO_VC1 -> "VC-1"
		MimeTypes.AUDIO_AAC, "audio/mp4a-latm" -> "AAC"
		MimeTypes.AUDIO_AC3 -> "Dolby Digital"
		MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
		MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (DD+)"
		MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
		MimeTypes.AUDIO_DTS -> "DTS"
		MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
		MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
		MimeTypes.AUDIO_FLAC -> "FLAC"
		MimeTypes.AUDIO_OPUS -> "Opus"
		MimeTypes.AUDIO_VORBIS -> "Vorbis"
		MimeTypes.AUDIO_MPEG -> "MP3"
		MimeTypes.AUDIO_RAW -> "PCM"
		else -> mime.substringAfter('/')
	}

	/** "SDR · 8-bit", "HDR10 (PQ) · 10-bit", "HLG", … */
	@JvmStatic
	fun color(format: Format): String {
		val info: ColorInfo? = format.colorInfo
		val range = when (info?.colorTransfer) {
			C.COLOR_TRANSFER_ST2084 -> if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) "Dolby Vision" else "HDR10 (PQ)"
			C.COLOR_TRANSFER_HLG -> "HLG"
			else -> if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) "Dolby Vision" else "SDR"
		}
		val depth = info?.lumaBitdepth?.takeIf { it != Format.NO_VALUE }?.let { " · $it-bit" }.orEmpty()
		return range + depth
	}

	@JvmStatic
	fun channels(count: Int): String = when (count) {
		1 -> "mono"
		2 -> "stereo"
		6 -> "5.1"
		8 -> "7.1"
		else -> "$count ch"
	}
}
