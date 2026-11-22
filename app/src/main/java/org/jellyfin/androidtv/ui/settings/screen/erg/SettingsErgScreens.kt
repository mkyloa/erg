package org.jellyfin.androidtv.ui.settings.screen.erg

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.GenreRowType
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.preference.constant.CarouselSortBy
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

/** Picks the row type for one of the ten "extra row" slots on the home screen. */
@Composable
fun SettingsHomeCuratedRowScreen(index: Int) {
	val router = LocalRouter.current
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	val preference = userSettingPreferences.genrerows.getOrNull(index)
		?: return ListMessage { Text("Unknown row $index") }

	var rowType by rememberPreference(userSettingPreferences, preference)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_prefs).uppercase()) },
				headingContent = { Text(stringResource(R.string.erg_curated_row_i, index + 1)) },
			)
		}

		items(GenreRowType.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				trailingContent = { RadioButton(checked = rowType == entry) },
				onClick = {
					rowType = entry
					router.back()
				},
				modifier = Modifier.focusKey("curated_row_${entry.name}", initialFocus = rowType == entry),
			)
		}
	}
}

@Composable
fun SettingsHomeCarouselSortScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var sortBy by rememberPreference(userPreferences, UserPreferences.carouselSortBy)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.erg_carousel).uppercase()) },
				headingContent = { Text(stringResource(R.string.erg_carousel_sort)) },
			)
		}

		items(CarouselSortBy.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				trailingContent = { RadioButton(checked = sortBy == entry) },
				onClick = {
					sortBy = entry
					router.back()
				},
				modifier = Modifier.focusKey("carousel_sort_${entry.name}", initialFocus = sortBy == entry),
			)
		}
	}
}

private val themeSongVolumes = listOf(5, 10, 20, 30, 50, 75, 100)

@Composable
fun SettingsThemeSongsScreen() {
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	var enabled by rememberPreference(userSettingPreferences, userSettingPreferences.themeSongsEnabled)
	var volume by rememberPreference(userSettingPreferences, userSettingPreferences.themesongvolume)
	var movies by rememberPreference(userSettingPreferences, userSettingPreferences.themeSongsMovies)
	var series by rememberPreference(userSettingPreferences, userSettingPreferences.themeSongsSeries)
	var episodes by rememberPreference(userSettingPreferences, userSettingPreferences.themeSongsEpisodes)
	var archive by rememberPreference(userSettingPreferences, userSettingPreferences.themeSongsArchiveFallback)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(stringResource(R.string.erg_theme_songs)) },
				captionContent = { Text(stringResource(R.string.erg_theme_songs_caption)) },
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_theme_songs_enabled)) },
				trailingContent = { Checkbox(checked = enabled) },
				onClick = { enabled = !enabled },
				modifier = Modifier.focusKey("theme_songs_enabled"),
			)
		}

		if (enabled) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.erg_theme_songs_volume)) },
					captionContent = { Text(stringResource(R.string.erg_percent, volume)) },
					// Cycle through presets; the remote makes free-form entry awkward
					onClick = {
						volume = themeSongVolumes.firstOrNull { it > volume } ?: themeSongVolumes.first()
					},
					modifier = Modifier.focusKey("theme_songs_volume"),
				)
			}

			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.erg_theme_songs_movies)) },
					trailingContent = { Checkbox(checked = movies) },
					onClick = { movies = !movies },
				)
			}

			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.erg_theme_songs_series)) },
					trailingContent = { Checkbox(checked = series) },
					onClick = { series = !series },
				)
			}

			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.erg_theme_songs_episodes)) },
					trailingContent = { Checkbox(checked = episodes) },
					onClick = { episodes = !episodes },
				)
			}

			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.erg_theme_songs_archive)) },
					captionContent = { Text(stringResource(R.string.erg_theme_songs_archive_caption)) },
					trailingContent = { Checkbox(checked = archive) },
					onClick = { archive = !archive },
				)
			}
		}
	}
}

@Composable
fun SettingsPlayerEngineScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var engine by rememberPreference(userPreferences, UserPreferences.videoPlayerEngine)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback).uppercase()) },
				headingContent = { Text(stringResource(R.string.erg_player_engine)) },
			)
		}

		items(org.jellyfin.androidtv.preference.constant.VideoPlayerEngine.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				captionContent = {
					Text(stringResource(when (entry) {
						org.jellyfin.androidtv.preference.constant.VideoPlayerEngine.EXOPLAYER -> R.string.erg_player_exoplayer_caption
						org.jellyfin.androidtv.preference.constant.VideoPlayerEngine.MPV -> R.string.erg_player_mpv_caption
					}))
				},
				trailingContent = { RadioButton(checked = engine == entry) },
				onClick = {
					engine = entry
					router.back()
				},
				modifier = Modifier.focusKey("player_engine_${entry.name}", initialFocus = engine == entry),
			)
		}
	}
}

/** Routes added by Erg; merged into the settings route map. */
val ergSettingsRoutes = mapOf<String, org.jellyfin.androidtv.ui.navigation.RouteComposable>(
	Routes.HOME_CURATED_ROW to { context ->
		SettingsHomeCuratedRowScreen(context.parameters["index"]?.toIntOrNull() ?: -1)
	},
	Routes.HOME_CAROUSEL_SORT to { SettingsHomeCarouselSortScreen() },
	Routes.CUSTOMIZATION_THEME_SONGS to { SettingsThemeSongsScreen() },
	Routes.PLAYBACK_PLAYER_ENGINE to { SettingsPlayerEngineScreen() },
	Routes.HOME_PINNED to { SettingsHomePinnedScreen() },
	Routes.UPDATES to { SettingsUpdatesScreen() },
	Routes.HOME_PINNED_ROW to { context -> SettingsHomePinnedRowScreen(context.parameters["index"]?.toIntOrNull() ?: -1) },
	Routes.HOME_PINNED_ADD to { context ->
		val type = context.parameters["type"]?.let { name -> org.jellyfin.androidtv.ui.home.PinnedRowType.entries.firstOrNull { it.name == name } }
		if (type != null) SettingsHomePinnedAddScreen(type)
	},
)
