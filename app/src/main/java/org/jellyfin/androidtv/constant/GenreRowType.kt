package org.jellyfin.androidtv.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/**
 * All possible genre row types for manual ordering
 */
enum class GenreRowType(
	override val serializedName: String,
	override val nameRes: Int,
) : PreferenceEnum {
	SUGGESTED_MOVIES("suggestedmovies", R.string.show_suggested_movies_row),
	COLLECTIONS("collections", R.string.show_collections_row),
	DISCOVER_MOVIES("discovermovies", R.string.show_discover_movies_row),
	DISCOVER_SERIES("discoverseries", R.string.show_discover_series_row),
	RECENTLY_RELEASED("recentlyreleased", R.string.show_recently_released_row),
	WATCH_IT_AGAIN("watchitagain", R.string.show_watch_it_again_row),
	MUSIC("music", R.string.show_music_videos_row),
	// Erg: rows from Seerr; empty when Seerr isn't set up
	SEERR_AVAILABLE("seerravailable", R.string.seerr_home_row_available),
	SEERR_TRENDING("seerrtrending", R.string.seerr_home_row_trending),
	NONE("none", R.string.home_section_none),
}
