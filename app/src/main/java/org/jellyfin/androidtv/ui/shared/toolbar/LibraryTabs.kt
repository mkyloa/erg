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
import org.jellyfin.androidtv.ui.composable.item.rememberButtonShape
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import timber.log.Timber

/** Erg: at most this many libraries get a tab; the top bar has a fixed height and limited width. */
private const val MAX_LIBRARY_TABS = 5

/**
 * Erg: one text tab per library (Movies, Shows, Collections, ...) in the home top bar. Replaces the
 * library row that used to sit under the hero on the modern home screen.
 */
@Composable
fun LibraryTabs() {
	val userViewsRepository = koinInject<UserViewsRepository>()
	val itemLauncher = koinInject<ItemLauncher>()

	val libraries by produceState(emptyList<BaseItemDto>()) {
		value = runCatching {
			userViewsRepository.views.first()
				.filter { userViewsRepository.isSupported(it.collectionType) }
				.take(MAX_LIBRARY_TABS)
		}.onFailure { Timber.w(it, "Could not load libraries for the top bar") }.getOrDefault(emptyList())
	}

	libraries.forEach { library ->
		LibraryTab(
			name = library.name.orEmpty(),
			onClick = { itemLauncher.launchUserView(library) },
		)
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
