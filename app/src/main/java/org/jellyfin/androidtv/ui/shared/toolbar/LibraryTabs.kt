package org.jellyfin.androidtv.ui.shared.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.home.LibraryIcons
import org.jellyfin.androidtv.ui.composable.item.rememberButtonShape
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import timber.log.Timber

/** Erg: at most this many libraries get a tab; the top bar has a fixed height and limited width. */
private const val MAX_LIBRARY_TABS = 5

/**
 * Erg: one tab per library (Movies, Shows, Collections, ...) in the home top bar: an icon button
 * like the rest of the bar, or a text tab when the library has no icon (see [LibraryIcons]).
 * Replaces the library row that used to sit under the hero on the modern home screen.
 */
@Composable
fun LibraryTabs() {
	val userViewsRepository = koinInject<UserViewsRepository>()
	val itemLauncher = koinInject<ItemLauncher>()
	val userSettingPreferences = koinInject<UserSettingPreferences>()

	val libraries by produceState(emptyList<BaseItemDto>()) {
		value = runCatching {
			userViewsRepository.views.first()
				.filter { userViewsRepository.isSupported(it.collectionType) }
				.take(MAX_LIBRARY_TABS)
		}.onFailure { Timber.w(it, "Could not load libraries for the top bar") }.getOrDefault(emptyList())
	}
	// Re-read when the icons are changed in settings
	val revision = LibraryIcons.revision.intValue
	val icons = remember(libraries, revision) { LibraryIcons.resolve(libraries, LibraryIcons.load(userSettingPreferences)) }

	libraries.forEach { library ->
		val name = library.name.orEmpty()
		val onClick = { itemLauncher.launchUserView(library) }
		val icon = icons[library.id.toString()]
		// Erg: an icon makes it a regular top bar button (label on focus); otherwise a text tab
		if (icon != null) ToolbarButton(icon = icon, label = name, onClick = onClick)
		else LibraryTab(name = name, onClick = onClick)
	}
}

@Composable
private fun LibraryTab(name: String, onClick: () -> Unit) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val (background, content) = toolbarButtonColors(focused)

	Box(
		modifier = Modifier
			.height(ToolbarButtonHeight)
			.background(background, rememberButtonShape())
			.clickable(onClick = onClick, interactionSource = interactionSource, indication = null)
			.padding(horizontal = 12.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = name,
			color = content,
			fontSize = 14.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}
