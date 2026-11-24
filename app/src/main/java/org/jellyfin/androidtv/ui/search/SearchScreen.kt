package org.jellyfin.androidtv.ui.search

import androidx.compose.ui.res.painterResource
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.ImagePreloader
import org.koin.android.ext.android.inject
import timber.log.Timber

@Composable
fun SearchScreen(
	viewModel: SearchViewModel,
	onNavigateToItem: (BaseRowItem) -> Unit,
	onVoiceSearch: () -> Unit,
	modifier: Modifier = Modifier,
	initialQuery: String? = null
) {
	val context = LocalContext.current
	val searchResults by viewModel.searchResultsFlow.collectAsStateWithLifecycle()
	val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
	val seerrResults by viewModel.seerrResults.collectAsStateWithLifecycle()
	val trending by viewModel.trending.collectAsStateWithLifecycle()

	// Saveable so the query (and its results) are still there after visiting a result or Seerr
	var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialQuery ?: "") }
	// The result last opened, so Back lands on it instead of the search box and keyboard
	var lastOpenedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
	val returnFocus = remember { FocusRequester() }
	val navigation = org.koin.compose.koinInject<org.jellyfin.androidtv.ui.navigation.NavigationRepository>()
	// Erg: Seerr titles open their Discover page; the key brings focus back to the poster afterwards
	val openSeerr: (org.jellyfin.androidtv.seerr.SeerrResult) -> Unit = { item ->
		lastOpenedId = seerrKey(item)
		item.mediaType?.let { navigation.navigate(org.jellyfin.androidtv.ui.navigation.Destinations.seerrItem(item.id, it)) }
	}
	var isSearchFocused by remember { mutableStateOf(false) }
	var isVoiceButtonFocused by remember { mutableStateOf(false) }
	var isClearButtonFocused by remember { mutableStateOf(false) }

	val searchFieldFocusRequester = remember { FocusRequester() }
	val voiceButtonFocusRequester = remember { FocusRequester() }
	val clearButtonFocusRequester = remember { FocusRequester() }
	val launchVoiceSearch: () -> Unit = {
		onVoiceSearch()
	}

	val showKeyboard: () -> Unit = {
		val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
		imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
	}

	val hideKeyboard: () -> Unit = {
		val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
		imm.hideSoftInputFromWindow(null, 0)
	}

	LaunchedEffect(Unit) {
		try {
			val backgroundService: BackgroundService by (context as FragmentActivity).inject()
			backgroundService.clearBackgrounds()
		} catch (e: Exception) {
			Timber.e(e, "Error clearing backdrops in SearchScreen")
		}
	}

	LaunchedEffect(initialQuery) {
		if (!initialQuery.isNullOrBlank()) {
			query = initialQuery
			viewModel.searchImmediately(initialQuery)
			delay(200)
		} else {
			// Coming back with a restored query: show its results again (the view model is new)
			if (query.isNotBlank()) viewModel.searchImmediately(query)
			delay(100)
			if (lastOpenedId == null || query.isBlank()) searchFieldFocusRequester.requestFocus()
		}
	}

	LaunchedEffect(searchResults, seerrResults, trending) {
		val id = lastOpenedId ?: return@LaunchedEffect
		if (id.startsWith(SEERR_KEY_PREFIX)) {
			// A Seerr poster: its row may arrive after the library results
			val shown = if (query.isBlank()) trending else seerrResults
			if (shown.any { seerrKey(it) == id }) {
				delay(100)
				runCatching { returnFocus.requestFocus() }.onFailure { searchFieldFocusRequester.requestFocus() }
				lastOpenedId = null
			}
			return@LaunchedEffect
		}
		if (searchResults.any { group -> group.items.any { it.id.toString() == id } }) {
			delay(100)
			runCatching { returnFocus.requestFocus() }.onFailure { searchFieldFocusRequester.requestFocus() }
			lastOpenedId = null
		} else if (searchResults.isNotEmpty() && !isSearching) {
			lastOpenedId = null
			searchFieldFocusRequester.requestFocus()
		}
	}

	LaunchedEffect(searchResults) {
		if (searchResults.isNotEmpty()) {
			val imageHelper: ImageHelper by (context as FragmentActivity).inject()
			val width = 300
			val height = 450

			searchResults.forEach { group ->
				group.items.take(5).forEach { item ->
					val imageUrl = imageHelper.getPrimaryImageUrl(item, width, height)
					if (imageUrl != null) {
						ImagePreloader.preloadImages(context, listOf(imageUrl))
					}
				}
			}
		}
	}

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(
				color = with(LocalContext.current) {
					val attrs = intArrayOf(R.attr.defaultBackground)
					val typedArray = obtainStyledAttributes(attrs)
					val colorInt = typedArray.getColor(0, Color.Black.toArgb())
					typedArray.recycle()
					Color(colorInt)
				}
			)
			.padding(vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp)
	) {

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 20.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			TVButton(
				onClick = launchVoiceSearch,
				onFocusChanged = { isVoiceButtonFocused = it },
				focusRequester = voiceButtonFocusRequester,
				isFocused = isVoiceButtonFocused
			) {
				Icon(
					painterResource(R.drawable.ic_mic),
					contentDescription = stringResource(R.string.lbl_voice_search),
					tint = if (isVoiceButtonFocused) org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.error else org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
					modifier = Modifier.size(24.dp)
				)
			}

			TVSearchFieldWithKeyboard(
				value = query,
				onValueChange = { newQuery ->
					query = newQuery
					viewModel.searchDebounced(newQuery)
					if (newQuery.isEmpty()) {
						hideKeyboard()
					}
				},
				onSearch = {
					if (query.isNotBlank()) {
						viewModel.searchImmediately(query)
						hideKeyboard()
					}
				},
				onFocusChanged = {
					isSearchFocused = it
					if (it) {
						showKeyboard()
					}
				},
				focusRequester = searchFieldFocusRequester,
				modifier = Modifier.weight(1f)
			)

			if (query.isNotEmpty()) {
				TVButton(
					onClick = {
						query = ""
						viewModel.searchDebounced("")
						searchFieldFocusRequester.requestFocus()
					},
					onFocusChanged = { isClearButtonFocused = it },
					focusRequester = clearButtonFocusRequester,
					isFocused = isClearButtonFocused
				) {
					Icon(
						Icons.Default.Close,
						contentDescription = stringResource(R.string.lbl_cancel),
						tint = if (isClearButtonFocused) org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onHighlight else org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
						modifier = Modifier.size(24.dp)
					)
				}
			}
		}

		AnimatedVisibility(
			visible = query.isNotBlank() && (searchResults.isNotEmpty() || seerrResults.isNotEmpty()),
			enter = fadeIn(),
			exit = fadeOut()
		) {
			SearchResultsList(
				results = searchResults.toList(),
				seerrResults = seerrResults,
				onSeerrClicked = openSeerr,
				onItemClicked = { item ->
					lastOpenedId = item.itemId.toString()
					onNavigateToItem(item)
				},
				returnId = lastOpenedId,
				returnFocus = returnFocus,
				modifier = Modifier
					.fillMaxWidth()
					.weight(1f)
			)
		}

		// While the first results for a query are on their way, show placeholders rather than "no results"
		if (searchResults.isEmpty() && query.isNotBlank() && isSearching) {
			org.jellyfin.androidtv.ui.composable.SkeletonGroup {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.weight(1f)
						.padding(horizontal = 48.dp, vertical = 16.dp),
					verticalArrangement = Arrangement.spacedBy(28.dp),
				) {
					repeat(2) { org.jellyfin.androidtv.ui.composable.SkeletonPosterRow(cardWidth = 120.dp, cardHeight = 180.dp) }
				}
			}
		}

		if (searchResults.isEmpty() && seerrResults.isEmpty() && query.isNotBlank() && !isSearching) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.weight(1f),
				contentAlignment = Alignment.Center
			) {
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Icon(
						Icons.Default.Search,
						contentDescription = null,
						tint = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.textMuted,
						modifier = Modifier.size(64.dp)
					)
					Text(
						text = stringResource(R.string.no_results_found),
						color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
						style = MaterialTheme.typography.headlineSmall
					)
					Text(
						text = stringResource(R.string.lbl_search_try),
						color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.textMuted,
						style = MaterialTheme.typography.bodyMedium
					)
				}
			}
		}

		if (query.isEmpty() && trending.isNotEmpty()) {
			// Erg: something to browse before typing; these are requestable from their Discover page
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.weight(1f)
					.padding(top = 24.dp),
			) {
				SeerrResultRow(
					title = stringResource(R.string.seerr_row_trending),
					items = trending,
					onClick = openSeerr,
					returnId = lastOpenedId,
					returnFocus = returnFocus,
				)
			}
		} else if (query.isEmpty()) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.weight(1f),
				contentAlignment = Alignment.Center
			) {
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Icon(
						Icons.Default.Search,
						contentDescription = null,
						tint = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.textMuted,
						modifier = Modifier.size(64.dp)
					)
					Text(
						text = stringResource(R.string.start_typing),
						color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
						style = MaterialTheme.typography.headlineSmall
					)
					Text(
						text = stringResource(R.string.use_keyboard_or),
						color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.textMuted,
						style = MaterialTheme.typography.bodyMedium
					)
				}
			}
		}
	}
}

@Composable
private fun TVSearchFieldWithKeyboard(
	value: String,
	onValueChange: (String) -> Unit,
	onSearch: () -> Unit,
	onFocusChanged: (Boolean) -> Unit,
	focusRequester: FocusRequester,
	modifier: Modifier = Modifier
) {
	var isFocused by remember { mutableStateOf(false) }
	val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		modifier = modifier
			.height(56.dp)
			// Single-line field: Up/Down would only move the cursor, so hand them to focus
			// navigation to reach the results (and the "Search Seerr" button) with the remote
			.onPreviewKeyEvent { event ->
				val direction = when (event.key) {
					Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
					Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
					else -> null
				}
				if (direction == null) false
				else {
					if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
					true
				}
			}
			.focusRequester(focusRequester)
			.onFocusChanged { focusState ->
				isFocused = focusState.isFocused
				onFocusChanged(focusState.isFocused)
			},
		placeholder = {
			Text(
				stringResource(R.string.lbl_search_hint),
				color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.textMuted
			)
		},
		leadingIcon = {
			Icon(
				Icons.Default.Search,
				contentDescription = stringResource(R.string.lbl_search_hint),
				tint = if (isFocused) org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text else org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.textMuted
			)
		},
		colors = OutlinedTextFieldDefaults.colors(
			focusedTextColor = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
			unfocusedTextColor = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
			cursorColor = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
			focusedBorderColor = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
			unfocusedBorderColor = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.textMuted.copy(alpha = 0.5f),
			focusedContainerColor = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text.copy(alpha = 0.15f),
			unfocusedContainerColor = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text.copy(alpha = 0.08f)
		),
		keyboardOptions = KeyboardOptions(
			imeAction = ImeAction.Search
		),
		keyboardActions = KeyboardActions(
			onSearch = { onSearch() }
		),
		singleLine = true,
		shape = RoundedCornerShape(8.dp),
		textStyle = TextStyle(
			fontSize = 18.sp,
			color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text
		)
	)
}

@Composable
private fun TVButton(
	onClick: () -> Unit,
	onFocusChanged: (Boolean) -> Unit,
	focusRequester: FocusRequester,
	isFocused: Boolean,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit
) {
	Box(
		modifier = modifier
			.size(50.dp)
			.background(
				color = if (isFocused) org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text else org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text.copy(alpha = 0.08f),
				shape = CircleShape
			)
			.border(
				width = if (isFocused) 2.dp else 1.dp,
				color = if (isFocused) org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text else org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.textMuted.copy(alpha = 0.5f),
				shape = CircleShape
			)
			.focusRequester(focusRequester)
			.onFocusChanged { onFocusChanged(it.isFocused) }
			.focusable()
			.onKeyEvent { keyEvent ->
				if (keyEvent.type == KeyEventType.KeyDown &&
					(keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter)
				) {
					onClick()
					true
				} else {
					false
				}
			},
		contentAlignment = Alignment.Center
	) {
		content()
	}
}

@Composable
private fun SearchResultsList(
	results: List<SearchResultGroup>,
	seerrResults: List<org.jellyfin.androidtv.seerr.SeerrResult>,
	onSeerrClicked: (org.jellyfin.androidtv.seerr.SeerrResult) -> Unit,
	onItemClicked: (BaseRowItem) -> Unit,
	returnId: String?,
	returnFocus: FocusRequester,
	modifier: Modifier = Modifier
) {

	LazyColumn(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(34.dp)
	) {
		items(results, key = { it.labelRes }) { group ->
			SearchResultGroup(
				title = stringResource(group.labelRes),
				items = group.items.toList(),
				returnId = returnId,
				returnFocus = returnFocus,
				onItemClick = { baseItemDto ->
					val baseRowItem = BaseItemDtoBaseRowItem(baseItemDto)
					onItemClicked(baseRowItem)
				}
			)
		}

		// Erg: after the library's results, what Seerr has that the library does not
		if (seerrResults.isNotEmpty()) item(key = "seerr") {
			SeerrResultRow(
				title = stringResource(R.string.search_not_in_library),
				items = seerrResults,
				onClick = onSeerrClicked,
				returnId = returnId,
				returnFocus = returnFocus,
			)
		}
	}
}

private const val SEERR_KEY_PREFIX = "seerr:"
private fun seerrKey(item: org.jellyfin.androidtv.seerr.SeerrResult) = "$SEERR_KEY_PREFIX${item.mediaType}-${item.id}"

/** A row of Seerr posters (with request status) in the same style as the library's result rows. */
@Composable
private fun SeerrResultRow(
	title: String,
	items: List<org.jellyfin.androidtv.seerr.SeerrResult>,
	onClick: (org.jellyfin.androidtv.seerr.SeerrResult) -> Unit,
	returnId: String?,
	returnFocus: FocusRequester,
) {
	Column(
		modifier = Modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Text(
			text = title.uppercase(),
			color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.padding(horizontal = 20.dp)
		)

		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			contentPadding = PaddingValues(horizontal = 20.dp)
		) {
			items(items, key = { seerrKey(it) }) { item ->
				org.jellyfin.androidtv.seerr.ui.PosterCard(
					item = item,
					onClick = { onClick(item) },
					modifier = if (seerrKey(item) == returnId) Modifier.focusRequester(returnFocus) else Modifier,
				)
			}
		}
	}
}

@Composable
private fun SearchResultGroup(
	title: String,
	items: List<org.jellyfin.sdk.model.api.BaseItemDto>,
	returnId: String?,
	returnFocus: FocusRequester,
	onItemClick: (org.jellyfin.sdk.model.api.BaseItemDto) -> Unit
) {
	Column(
		modifier = Modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Text(
			text = title.uppercase(),
			color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.padding(horizontal = 20.dp)
		)

		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			contentPadding = PaddingValues(horizontal = 20.dp)
		) {
			items(items, key = { it.id }) { item ->
				SearchItemCard(
					item = item,
					onClick = { onItemClick(item) },
					focusRequester = returnFocus.takeIf { item.id.toString() == returnId },
				)
			}
		}
	}
}

@Composable
private fun SearchItemCard(
	item: org.jellyfin.sdk.model.api.BaseItemDto,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	focusRequester: FocusRequester? = null,
) {
	val context = LocalContext.current
	val imageHelper: ImageHelper by (context as FragmentActivity).inject()
	var isCardFocused by remember { mutableStateOf(false) }
	val cardShape = org.jellyfin.androidtv.ui.composable.item.rememberCardShape()

	val (cardWidth, cardHeight) = when (item.type) {
		org.jellyfin.sdk.model.api.BaseItemKind.EPISODE -> Pair(220, 110)
		org.jellyfin.sdk.model.api.BaseItemKind.MUSIC_VIDEO -> Pair(220, 110)
		org.jellyfin.sdk.model.api.BaseItemKind.BOX_SET -> Pair(220, 110)
		else -> Pair(120, 180)
	}

	Column(
		modifier = modifier.width(cardWidth.dp)
	) {
		Box(
			modifier = Modifier
				.height(cardHeight.dp)
				.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
				.onFocusChanged { isCardFocused = it.isFocused }
				.scale(
					androidx.compose.animation.core.animateFloatAsState(
						targetValue = if (isCardFocused) 1.10f else 1.0f,
						animationSpec = androidx.compose.animation.core.tween(150),
						label = "search-card-scale",
					).value
				)
				.border(
					width = 2.dp,
					color = if (isCardFocused) {
						with(LocalContext.current) {
							val attrs = intArrayOf(R.attr.focusBorderColor)
							val typedArray = obtainStyledAttributes(attrs)
							val colorInt = typedArray.getColor(0, Color.White.toArgb())
							typedArray.recycle()
							Color(colorInt)
						}
					} else {
						org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text.copy(alpha = 0.2f)
					},
					shape = cardShape
				)
				.clip(cardShape)
				.clickable { onClick() }
		) {
			AsyncImage(
				model = imageHelper.getPrimaryImageUrl(item, cardWidth * 2, cardHeight * 2),
				contentDescription = item.name,
				modifier = Modifier.fillMaxSize(),
				contentScale = androidx.compose.ui.layout.ContentScale.Crop
			)
		}

		Box(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 1.dp, vertical = 15.dp)
		) {
			val displayText = when (item.type) {
				org.jellyfin.sdk.model.api.BaseItemKind.EPISODE -> {
					val seriesName = item.seriesName ?: ""
					val episodeName = item.name ?: ""
					val seasonNumber = item.parentIndexNumber ?: 0
					val episodeNumber = item.indexNumber ?: 0

					if (seriesName.isNotBlank() && episodeName.isNotBlank()) {
						"$seriesName\nS${seasonNumber}E${episodeNumber}- $episodeName"
					} else {
						item.name ?: ""
					}
				}
				else -> item.name ?: ""
			}

			Text(
				text = displayText,
				color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 2
			)
		}
	}
}
