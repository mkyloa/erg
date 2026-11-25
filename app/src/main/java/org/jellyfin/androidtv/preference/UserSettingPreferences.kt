package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.constant.GenreRowType
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.preference.store.DisplayPreferencesStore
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.intPreference
import org.jellyfin.preference.stringPreference
import org.jellyfin.sdk.api.client.ApiClient

class UserSettingPreferences(
	api: ApiClient,
) : DisplayPreferencesStore(
	displayPreferencesId = "usersettings",
	api = api,
	app = "emby",
) {
	companion object {
		val skipBackLength = intPreference("skipBackLength", 10_000)
		val skipForwardLength = intPreference("skipForwardLength", 30_000)

		val homesection0 = enumPreference("homesection0", HomeSectionType.LIBRARY_TILES_SMALL)
		val homesection1 = enumPreference("homesection1", HomeSectionType.RESUME)
		val homesection2 = enumPreference("homesection2", HomeSectionType.RESUME_AUDIO)
		val homesection3 = enumPreference("homesection3", HomeSectionType.RESUME_BOOK)
		val homesection4 = enumPreference("homesection4", HomeSectionType.LIVE_TV)
		val homesection5 = enumPreference("homesection5", HomeSectionType.NEXT_UP)
		val homesection6 = enumPreference("homesection6", HomeSectionType.LATEST_MEDIA)
		val homesection7 = enumPreference("homesection7", HomeSectionType.NONE)
		val homesection8 = enumPreference("homesection8", HomeSectionType.NONE)
		val homesection9 = enumPreference("homesection9", HomeSectionType.NONE)
	}

	// Erg additions (instance members, as the code ported from DUNE expects)
	val genrerow0 = enumPreference("genrerow0", GenreRowType.SUGGESTED_MOVIES)
	val genrerow1 = enumPreference("genrerow1", GenreRowType.COLLECTIONS)
	val genrerow2 = enumPreference("genrerow2", GenreRowType.DISCOVER_MOVIES)
	val genrerow3 = enumPreference("genrerow3", GenreRowType.DISCOVER_SERIES)
	val genrerow4 = enumPreference("genrerow4", GenreRowType.RECENTLY_RELEASED)
	val genrerow5 = enumPreference("genrerow5", GenreRowType.WATCH_IT_AGAIN)
	val genrerow6 = enumPreference("genrerow6", GenreRowType.MUSIC)
	val genrerow7 = enumPreference("genrerow7", GenreRowType.NONE)
	val genrerow8 = enumPreference("genrerow8", GenreRowType.NONE)
	val genrerow9 = enumPreference("genrerow9", GenreRowType.NONE)
	/** Rows pinned to the home screen (JSON, see PinnedRows) */
	val pinnedRows = stringPreference("erg_pinned_rows", "")

	/** Erg: top bar icon per library, JSON map of library id to LibraryIcon name */
	val libraryIcons = stringPreference("erg_library_icons", "")

	val genrerows = listOf(genrerow0, genrerow1, genrerow2, genrerow3, genrerow4, genrerow5, genrerow6, genrerow7, genrerow8, genrerow9)

	val themeSongsEnabled = booleanPreference("themesongsEnabled", false)
	val themesongvolume = intPreference("themesongsVolume", 10)
	val themeSongsMovies = booleanPreference("themeSongsMovies", true)
	val themeSongsSeries = booleanPreference("themeSongsSeries", true)
	val themeSongsEpisodes = booleanPreference("themeSongsEpisodes", false)
	val themeSongsArchiveFallback = booleanPreference("themeSongsArchiveFallback", false)

	val useExtraSmallMediaFolders = booleanPreference("useExtraSmallMediaFolders", true)
	val showLiveTvButton = booleanPreference("show_live_tv_button", false)
	val showRandomButton = booleanPreference("show_masks_button", true)
	val useClassicHomeScreen = booleanPreference("use_classic_home_screen", false)
	val showMusicVideosRow = booleanPreference("show_music_videos_row", false)

	val homesections = listOf(
		homesection0,
		homesection1,
		homesection2,
		homesection3,
		homesection4,
		homesection5,
		homesection6,
		homesection7,
		homesection8,
		homesection9,
	)

	val activeHomesections
		get() = homesections
			.map(::get)
			.filterNot { it == HomeSectionType.NONE }
}
