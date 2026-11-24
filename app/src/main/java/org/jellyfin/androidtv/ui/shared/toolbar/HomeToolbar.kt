package org.jellyfin.androidtv.ui.shared.toolbar

import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.item.rememberButtonShape
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.koin.compose.koinInject
import timber.log.Timber

/** Height of every top bar button; icons stay one size and the label slides in on focus. */
internal val ToolbarButtonHeight = 32.dp
private val ToolbarIconSize = 18.dp
private const val ToolbarAnimationMs = 160

@RequiresApi(Build.VERSION_CODES.M)
@Composable
fun HomeToolbar(
	openSearch: () -> Unit,
	openLiveTv: () -> Unit,
	openSettings: () -> Unit,
	switchUsers: () -> Unit,
	openRandomMovie: (BaseItemDto) -> Unit = { _ -> },
	openLibrary: () -> Unit = {},
	onFavoritesClick: () -> Unit = {},
	openDiscover: () -> Unit = {},
	userSettingPreferences: UserSettingPreferences = koinInject(),
	userRepository: UserRepository = koinInject(),
	@Suppress("UNUSED_PARAMETER") lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
	val showLiveTvButton = userSettingPreferences.get(userSettingPreferences.showLiveTvButton)
	val showRandomButton = userSettingPreferences.get(userSettingPreferences.showRandomButton)

	// Centered on its resting width. Focused buttons widen into labeled pills; they grow to the
	// right instead of re-centering and shifting the whole bar.
	val restingWidth = remember { IntArray(1) }
	var barFocused by remember { mutableStateOf(false) }
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.onFocusChanged { barFocused = it.hasFocus }
	) {
		Row(
			modifier = Modifier
				.layout { measurable, constraints ->
					val placeable = measurable.measure(constraints.copy(minWidth = 0))
					if (!barFocused || restingWidth[0] == 0) restingWidth[0] = placeable.width
					layout(constraints.maxWidth, placeable.height) {
						placeable.place(((constraints.maxWidth - restingWidth[0]) / 2).coerceAtLeast(0), 0)
					}
				}
				.padding(top = 14.dp),
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			UserButton(userRepository = userRepository, onClick = switchUsers)
			ToolbarButton(R.drawable.ic_search, stringResource(R.string.lbl_search), openSearch)
			ToolbarButton(R.drawable.ic_house, stringResource(R.string.erg_home), openLibrary)
			LibraryTabs()
			if (showLiveTvButton) ToolbarButton(R.drawable.ic_live, stringResource(R.string.lbl_live_tv), openLiveTv)
			if (showRandomButton) RandomMovieButton(openRandomMovie)
			ToolbarButton(R.drawable.ic_settings, stringResource(R.string.lbl_settings), openSettings)
			ToolbarButton(R.drawable.ic_heart, stringResource(R.string.lbl_favorites), onFavoritesClick)
			ToolbarButton(R.drawable.ic_discover, stringResource(R.string.seerr_discover), openDiscover)
		}
	}
}

/** Background and content colors of a top bar button, animated with focus. */
@Composable
internal fun toolbarButtonColors(focused: Boolean): Pair<Color, Color> {
	val colors = JellyfinTheme.colorScheme
	val background by animateColorAsState(
		if (focused) colors.onBackground.copy(alpha = 0.9f) else Color.Transparent,
		tween(ToolbarAnimationMs),
		label = "toolbar-button-background",
	)
	val content by animateColorAsState(
		if (focused) colors.background else colors.onBackground,
		tween(ToolbarAnimationMs),
		label = "toolbar-button-content",
	)
	return background to content
}

/**
 * Erg: an icon button that widens into a labeled pill when focused. The icon keeps its size and the
 * label fades and slides in, so the only thing that moves is the pill growing to fit the label.
 */
@Composable
private fun ToolbarButton(
	@DrawableRes icon: Int,
	label: String,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val (background, content) = toolbarButtonColors(focused)

	Row(
		modifier = Modifier
			.height(ToolbarButtonHeight)
			.defaultMinSize(minWidth = ToolbarButtonHeight)
			.background(background, rememberButtonShape())
			.clickable(onClick = onClick, interactionSource = interactionSource, indication = null)
			.padding(horizontal = 7.dp),
		horizontalArrangement = Arrangement.Center,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(
			painter = painterResource(icon),
			contentDescription = label,
			tint = content,
			modifier = Modifier.size(ToolbarIconSize),
		)

		AnimatedVisibility(
			visible = focused,
			enter = expandHorizontally(tween(ToolbarAnimationMs)) + fadeIn(tween(ToolbarAnimationMs)),
			exit = shrinkHorizontally(tween(ToolbarAnimationMs)) + fadeOut(tween(ToolbarAnimationMs / 2)),
		) {
			Text(
				text = label,
				color = content,
				fontSize = 14.sp,
				maxLines = 1,
				modifier = Modifier.padding(start = 6.dp, end = 4.dp),
			)
		}
	}
}

/** The current user's avatar; a ring of the focus color shows when it is focused. */
@Composable
private fun UserButton(userRepository: UserRepository, onClick: () -> Unit) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val (background, content) = toolbarButtonColors(focused)
	val currentUser by userRepository.currentUser.collectAsState()
	val api = koinInject<ApiClient>()
	val imageUrl = currentUser?.let { user ->
		user.primaryImageTag?.let { tag -> api.imageApi.getUserImageUrl(userId = user.id, tag = tag) }
	}

	Box(
		modifier = Modifier
			.size(ToolbarButtonHeight)
			.background(background, CircleShape)
			.clickable(onClick = onClick, interactionSource = interactionSource, indication = null),
		contentAlignment = Alignment.Center,
	) {
		if (imageUrl != null) {
			AsyncImage(
				model = imageUrl,
				contentDescription = stringResource(R.string.lbl_switch_user),
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.size(26.dp)
					.clip(CircleShape),
			)
		} else {
			Icon(
				painter = painterResource(R.drawable.ic_user),
				contentDescription = stringResource(R.string.lbl_switch_user),
				tint = content,
				modifier = Modifier.size(ToolbarIconSize),
			)
		}
	}
}

/** Opens a random movie from the Movies library. */
@Composable
private fun RandomMovieButton(openRandomMovie: (BaseItemDto) -> Unit) {
	val context = LocalContext.current
	val api = koinInject<ApiClient>()
	val userViewsRepository = koinInject<UserViewsRepository>()
	val coroutineScope = rememberCoroutineScope()

	ToolbarButton(R.drawable.ic_shuffle, stringResource(R.string.lbl_random)) {
		coroutineScope.launch {
			val movie = runCatching {
				withContext(Dispatchers.IO) {
					val movies = userViewsRepository.views.first().find {
						it.collectionType == CollectionType.MOVIES || it.name?.equals("Movies", ignoreCase = true) == true
					} ?: return@withContext null
					api.itemsApi.getItems(
						parentId = movies.id,
						includeItemTypes = listOf(BaseItemKind.MOVIE),
						recursive = true,
						sortBy = listOf(ItemSortBy.RANDOM),
						limit = 1,
					).content.items.firstOrNull()
				}
			}.onFailure { Timber.e(it, "Error getting random movie") }.getOrNull()

			// Back on the main thread: toasts from a background thread crash
			if (movie != null) openRandomMovie(movie)
			else Toast.makeText(context, R.string.erg_random_none, Toast.LENGTH_LONG).show()
		}
	}
}
