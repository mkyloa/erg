package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import androidx.compose.runtime.setValue
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsHomeScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val userSettingPreferences = koinInject<UserSettingPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_prefs)) },
			)
		}

		itemsIndexed(userSettingPreferences.homesections) { index, section ->
			ListButton(
				headingContent = { Text(stringResource(R.string.home_section_i, index + 1)) },
				captionContent = { Text(stringResource(userSettingPreferences[section].nameRes)) },
				onClick = { router.push(Routes.HOME_SECTION, mapOf("index" to index.toString())) },
				modifier = Modifier.focusKey("home_section_$index")
			)
		}

		item {
			ListSection(
				headingContent = { Text(stringResource(R.string.lbl_next_up)) },
			)
		}

		item {
			val homeNextUpMaxDays by rememberPreference(userPreferences, UserPreferences.homeNextUpMaxDays)
			val options = getNextUpCutoffOptions()
			val selectedCaption = options.firstOrNull { it.first == homeNextUpMaxDays }?.second
				?: stringResource(R.string.home_next_up_max_days_disabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.home_next_up_max_days)) },
				captionContent = { Text(selectedCaption) },
				onClick = { router.push(Routes.HOME_NEXT_UP_CUTOFF) },
				modifier = Modifier.focusKey(Routes.HOME_NEXT_UP_CUTOFF)
			)
		}
		// --- Erg (from DUNE) ---
		item { ListSection(headingContent = { Text(stringResource(R.string.erg_layout)) }) }

		item {
			var classic by rememberPreference(userSettingPreferences, userSettingPreferences.useClassicHomeScreen)
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_classic_home)) },
				captionContent = { Text(stringResource(R.string.erg_classic_home_caption)) },
				trailingContent = { Checkbox(checked = classic) },
				onClick = { classic = !classic },
				modifier = Modifier.focusKey("erg_classic_home"),
			)
		}

		item {
			var compact by rememberPreference(userSettingPreferences, userSettingPreferences.useExtraSmallMediaFolders)
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_compact_libraries)) },
				captionContent = { Text(stringResource(R.string.erg_compact_libraries_caption)) },
				trailingContent = { Checkbox(checked = compact) },
				onClick = { compact = !compact },
			)
		}

		item {
			var liveTv by rememberPreference(userSettingPreferences, userSettingPreferences.showLiveTvButton)
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_toolbar_live_tv)) },
				trailingContent = { Checkbox(checked = liveTv) },
				onClick = { liveTv = !liveTv },
			)
		}

		item {
			var random by rememberPreference(userSettingPreferences, userSettingPreferences.showRandomButton)
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_toolbar_random)) },
				trailingContent = { Checkbox(checked = random) },
				onClick = { random = !random },
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.erg_carousel)) }) }

		item {
			val sortBy by rememberPreference(userPreferences, UserPreferences.carouselSortBy)
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_carousel_sort)) },
				captionContent = { Text(stringResource(sortBy.nameRes)) },
				onClick = { router.push(Routes.HOME_CAROUSEL_SORT) },
				modifier = Modifier.focusKey(Routes.HOME_CAROUSEL_SORT),
			)
		}

		item {
			var includeSeries by rememberPreference(userPreferences, UserPreferences.carouselIncludeSeries)
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_carousel_include_series)) },
				trailingContent = { Checkbox(checked = includeSeries) },
				onClick = { includeSeries = !includeSeries },
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.erg_curated_rows)) }) }

		itemsIndexed(userSettingPreferences.genrerows) { index, row ->
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_curated_row_i, index + 1)) },
				captionContent = { Text(stringResource(userSettingPreferences[row].nameRes)) },
				onClick = { router.push(Routes.HOME_CURATED_ROW, mapOf("index" to index.toString())) },
				modifier = Modifier.focusKey("curated_row_$index"),
			)
		}
	}
}
