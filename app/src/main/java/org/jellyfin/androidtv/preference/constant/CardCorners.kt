package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/** Erg: corner radius of poster and thumbnail cards. */
enum class CardCorners(
	override val nameRes: Int,
	val radiusDp: Int,
) : PreferenceEnum {
	SQUARE(R.string.erg_card_corners_square, 0),
	SMALL(R.string.erg_card_corners_small, 4),
	MEDIUM(R.string.erg_card_corners_medium, 8),
	LARGE(R.string.erg_card_corners_large, 12),
}
