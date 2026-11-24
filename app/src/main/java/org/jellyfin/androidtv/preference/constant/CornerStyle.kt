package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/** Erg: corner style for cards, the home banner and buttons. [ROUND] means fully rounded ends. */
enum class CornerStyle(
	override val nameRes: Int,
	val radiusDp: Int,
) : PreferenceEnum {
	SQUARE(R.string.erg_card_corners_square, 0),
	SMALL(R.string.erg_card_corners_small, 4),
	MEDIUM(R.string.erg_card_corners_medium, 8),
	LARGE(R.string.erg_card_corners_large, 12),
	ROUND(R.string.erg_corners_round, -1),
}
