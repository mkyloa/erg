package org.jellyfin.androidtv.ui.settings.screen.erg

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.home.LibraryIcon
import org.jellyfin.androidtv.ui.home.LibraryIcons
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject

@Composable
private fun rememberLibraries(): List<BaseItemDto> {
	val userViewsRepository = koinInject<UserViewsRepository>()
	val libraries by produceState(emptyList<BaseItemDto>()) {
		value = runCatching {
			userViewsRepository.views.first().filter { userViewsRepository.isSupported(it.collectionType) }
		}.getOrDefault(emptyList())
	}
	return libraries
}

/** Erg: the libraries in the top bar, each with the icon (or name) it shows there. */
@Composable
fun SettingsLibraryIconsScreen() {
	val router = LocalRouter.current
	val prefs = koinInject<UserSettingPreferences>()
	val libraries = rememberLibraries()
	val revision = LibraryIcons.revision.intValue
	val choices = remember(revision) { LibraryIcons.load(prefs) }
	val shown = remember(libraries, choices) { LibraryIcons.resolve(libraries, choices) }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(stringResource(R.string.erg_library_icons)) },
				captionContent = { Text(stringResource(R.string.erg_library_icons_caption)) },
			)
		}

		items(libraries, key = { it.id.toString() }) { library ->
			val id = library.id.toString()
			val choice = choices[id] ?: LibraryIcon.AUTO
			val icon = shown[id]
			ListButton(
				leadingContent = {
					if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
				},
				headingContent = { Text(library.name.orEmpty()) },
				captionContent = {
					// Automatic that fell back to the name says so, so the result isn't a surprise
					val label = stringResource(choice.nameRes)
					Text(if (choice == LibraryIcon.AUTO && icon == null) "$label · ${stringResource(R.string.erg_library_icon_text)}" else label)
				},
				onClick = { router.push(Routes.CUSTOMIZATION_LIBRARY_ICON, mapOf("libraryId" to id)) },
				modifier = Modifier.focusKey("erg_library_icon_$id"),
			)
		}
	}
}

/** Erg: pick the top bar icon for one library. */
@Composable
fun SettingsLibraryIconScreen(libraryId: String) {
	val router = LocalRouter.current
	val prefs = koinInject<UserSettingPreferences>()
	val library = rememberLibraries().firstOrNull { it.id.toString() == libraryId }
	val revision = LibraryIcons.revision.intValue
	val current = remember(revision) { LibraryIcons.load(prefs)[libraryId] ?: LibraryIcon.AUTO }
	val automatic = LibraryIcons.automatic(library?.collectionType)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.erg_library_icons).uppercase()) },
				headingContent = { Text(library?.name.orEmpty()) },
			)
		}

		items(LibraryIcon.entries) { entry ->
			val preview = if (entry == LibraryIcon.AUTO) automatic.icon else entry.icon
			ListButton(
				leadingContent = {
					if (preview != null) Icon(painterResource(preview), contentDescription = null, modifier = Modifier.size(24.dp))
				},
				headingContent = { Text(stringResource(entry.nameRes)) },
				trailingContent = { RadioButton(checked = current == entry) },
				onClick = {
					LibraryIcons.set(prefs, libraryId, entry)
					router.back()
				},
				modifier = Modifier.focusKey("erg_library_icon_${libraryId}_${entry.name}", initialFocus = current == entry),
			)
		}
	}
}
