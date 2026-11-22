package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.HdrFormat
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsPlaybackHdrOverridesScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback_advanced).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_hdr_overrides)) },
				captionContent = { Text(stringResource(R.string.preference_hdr_overrides_summary)) },
			)
		}

		items(HdrFormat.entries) { format ->
			val mode = userPreferences[format.preference]

			ListButton(
				headingContent = { Text(stringResource(format.nameRes)) },
				captionContent = { Text(stringResource(mode.nameRes)) },
				onClick = {
					router.push(
						route = Routes.PLAYBACK_HDR_OVERRIDE,
						parameters = mapOf("format" to format.name),
					)
				},
				modifier = Modifier.focusKey("hdr_overrides_${format.name}")
			)
		}

		// Erg: Dolby Vision profile 7 base-layer fallback
		item {
			var fallback by org.jellyfin.androidtv.ui.settings.compat.rememberPreference(userPreferences, UserPreferences.doviProfile7Fallback)
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_dovi_p7_fallback)) },
				captionContent = { Text(stringResource(R.string.erg_dovi_p7_fallback_caption)) },
				trailingContent = { org.jellyfin.androidtv.ui.base.form.Checkbox(checked = fallback) },
				onClick = { fallback = !fallback },
				modifier = Modifier.focusKey("erg_dovi_p7_fallback"),
			)
		}
	}
}
