package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/** Which player plays video in the built-in player. */
enum class VideoPlayerEngine(
	override val nameRes: Int,
) : PreferenceEnum {
	/** ExoPlayer (Media3): best HDR / Dolby Vision support, uses the device's decoders. */
	EXOPLAYER(R.string.erg_player_exoplayer),

	/** mpv: direct-plays nearly anything with full ASS subtitle rendering; HDR is tone-mapped. */
	MPV(R.string.erg_player_mpv),
}
