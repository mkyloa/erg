package org.jellyfin.androidtv.seerr.ui

import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.seerr.SeerrResult
import org.jellyfin.androidtv.seerr.seerrImageUrl
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.Icon

private val PosterWidth = 132.dp
private val PosterHeight = 198.dp

@Composable
fun SeerrDiscoverScreen(
	viewModel: SeerrDiscoverViewModel,
	initialItem: SeerrResult? = null,
	onCloseInitialItem: () -> Unit = {},
	onOpenSearch: () -> Unit,
	onOpenSettings: () -> Unit,
	onOpenLibraryItem: (String) -> Unit,
	onPlayTrailer: (String) -> Unit,
) {
	// Opened from search on one title: show its page; leaving it leaves Discover too
	LaunchedEffect(initialItem) { if (initialItem != null && viewModel.stack.value.isEmpty()) viewModel.openDetail(initialItem) }
	val state by viewModel.state.collectAsState()
	val stack by viewModel.stack.collectAsState()
	val detail = stack.lastOrNull()
	// Back walks back through the pages; from search, leaving the first page leaves Discover
	val back = {
		if (initialItem != null && viewModel.stack.value.size == 1) onCloseInitialItem() else viewModel.closeDetail()
	}
	val actions = remember(viewModel) { SeerrPageActions(viewModel, onOpenLibraryItem, onPlayTrailer, onBack = { back() }) }

	Box(
		Modifier
			.fillMaxSize()
			.background(org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.background)
	) {
		// Fade between loading skeleton, content and error states
		androidx.compose.animation.AnimatedContent(
			targetState = state,
			// Only animate when the kind of state changes, not on every update
			contentKey = { it::class },
			transitionSpec = {
				androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)) togetherWith
					androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300))
			},
			label = "seerr-discover-state",
		) { current ->
		when (current) {
			SeerrDiscoverState.NeedsSetup -> SetupNeeded(onOpenSettings)
			SeerrDiscoverState.Loading -> DiscoverSkeleton()
			is SeerrDiscoverState.Error -> Centered {
				Column(horizontalAlignment = Alignment.CenterHorizontally) {
					Text(stringResource(R.string.seerr_load_failed, current.message), color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground)
					Button(onClick = { viewModel.refresh() }, modifier = Modifier.padding(top = 16.dp)) {
						Text(stringResource(R.string.seerr_retry))
					}
				}
			}

			is SeerrDiscoverState.Content -> DiscoverContent(
				sections = current.sections,
				detailOpen = detail != null,
				onOpenSearch = onOpenSearch,
				onOpenRequests = viewModel::openRequests,
				onSelect = viewModel::openDetail,
				onSelectTile = viewModel::openBrowse,
				onNearEnd = viewModel::loadMore,
			)
		}
		}

		if (detail != null) BackHandler { back() }

		// Keep the last detail state around while it fades out
		var lastDetail by remember { mutableStateOf(detail) }
		if (detail != null) lastDetail = detail
		androidx.compose.animation.AnimatedVisibility(
			visible = detail != null,
			enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)),
			exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
		) {
			lastDetail?.let { detailState ->
				SeerrDetailScreen(
					entry = detailState,
					liveIds = stack.map { it.id }.toSet(),
					trapFocus = detail != null,
					actions = actions,
				)
			}
		}
	}
}

@Composable
private fun DiscoverContent(
	sections: List<SeerrSection>,
	detailOpen: Boolean,
	onOpenSearch: () -> Unit,
	onOpenRequests: () -> Unit,
	onSelect: (SeerrResult) -> Unit,
	onSelectTile: (SeerrTile) -> Unit,
	onNearEnd: (String) -> Unit,
) {
	val firstRowFocus = remember { FocusRequester() }
	// The poster a title page was opened from, so Back lands on it again
	var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
	val returnFocus = remember { FocusRequester() }
	val select: (String, SeerrResult) -> Unit = { key, item -> lastOpened = key; onSelect(item) }
	val selectTile: (String, SeerrTile) -> Unit = { key, tile -> lastOpened = key; onSelectTile(tile) }

	LaunchedEffect(Unit) { if (!detailOpen) runCatching { firstRowFocus.requestFocus() } }
	LaunchedEffect(detailOpen) {
		if (!detailOpen && lastOpened != null) runCatching { returnFocus.requestFocus() }
	}

	LazyColumn(
		// Rows run edge to edge so posters scroll off the screen instead of clipping at the inset
		contentPadding = PaddingValues(top = 27.dp, bottom = 48.dp),
		verticalArrangement = Arrangement.spacedBy(20.dp),
		modifier = Modifier.fillMaxSize(),
	) {
		item(key = "header") {
			Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = ScreenInset)) {
				Text(
					stringResource(R.string.seerr_discover),
					color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground,
					fontSize = 28.sp,
					fontWeight = FontWeight.Bold,
					modifier = Modifier.padding(end = 24.dp),
				)
				// Erg: one search for the library and Seerr; Discover is for browsing
				Button(onClick = onOpenSearch) {
					Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(18.dp))
					Text(stringResource(R.string.seerr_search_hint), modifier = Modifier.padding(start = 8.dp))
				}
				Button(onClick = { lastOpened = "requests"; onOpenRequests() }, modifier = Modifier
					.padding(start = 12.dp)
					.then(if (lastOpened == "requests") Modifier.focusRequester(returnFocus) else Modifier)) {
					Icon(painterResource(R.drawable.ic_time), contentDescription = null, modifier = Modifier.size(18.dp))
					Text(stringResource(R.string.seerr_requests), modifier = Modifier.padding(start = 8.dp))
				}
			}
		}

		val firstRowKey = sections.firstOrNull()?.key
		sections.forEach { section ->
			item(key = section.key) {
				when (section) {
					is SeerrRow -> PosterRow(
						rowKey = section.key,
						title = stringResource(section.title),
						items = section.items,
						onSelect = select,
						returnKey = lastOpened,
						returnFocus = returnFocus,
						firstItemFocus = if (section.key == firstRowKey) firstRowFocus else null,
						onNearEnd = if (section.hasMore) ({ onNearEnd(section.key) }) else null,
					)

					is SeerrTileRow -> TileRow(
						row = section,
						onSelect = selectTile,
						returnKey = lastOpened,
						returnFocus = returnFocus,
						firstItemFocus = if (section.key == firstRowKey) firstRowFocus else null,
					)
				}
			}
		}
	}
}

/** Left and right margin of the screen's content. */
internal val ScreenInset = 48.dp

@Composable
internal fun RowTitle(title: String) = Text(
	title,
	color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground,
	fontSize = 20.sp,
	modifier = Modifier.padding(start = ScreenInset, bottom = 10.dp),
)

@Composable
internal fun PosterRow(
	rowKey: String,
	title: String,
	items: List<SeerrResult>,
	onSelect: (String, SeerrResult) -> Unit,
	returnKey: String?,
	returnFocus: FocusRequester,
	firstItemFocus: FocusRequester? = null,
	onNearEnd: (() -> Unit)? = null,
) {
	val listState = androidx.compose.foundation.lazy.rememberLazyListState()
	if (onNearEnd != null) {
		val nearEnd by remember(items.size) {
			androidx.compose.runtime.derivedStateOf {
				(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= items.size - LOAD_MORE_AHEAD
			}
		}
		LaunchedEffect(nearEnd, items.size) { if (nearEnd) onNearEnd() }
	}

	Column {
		RowTitle(title)
		LazyRow(
			state = listState,
			horizontalArrangement = Arrangement.spacedBy(14.dp),
			contentPadding = PaddingValues(vertical = 4.dp, horizontal = ScreenInset),
		) {
			items(items, key = { "${it.mediaType}-${it.id}" }) { item ->
				// The same title can sit in several rows, so the row is part of the key
				val key = "$rowKey/${item.mediaType}-${item.id}"
				PosterCard(
					item = item,
					onClick = { onSelect(key, item) },
					modifier = Modifier
						.then(if (key == returnKey) Modifier.focusRequester(returnFocus) else Modifier)
						.then(if (firstItemFocus != null && item == items.first()) Modifier.focusRequester(firstItemFocus) else Modifier),
				)
			}
		}
	}
}

/** Rows ask for their next page this many posters before the end. */
private const val LOAD_MORE_AHEAD = 6

private val TileWidth = 240.dp
private val TileHeight = 135.dp

@Composable
private fun TileRow(
	row: SeerrTileRow,
	onSelect: (String, SeerrTile) -> Unit,
	returnKey: String?,
	returnFocus: FocusRequester,
	firstItemFocus: FocusRequester? = null,
) {
	Column {
		RowTitle(stringResource(row.title))
		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(14.dp),
			contentPadding = PaddingValues(vertical = 4.dp, horizontal = ScreenInset),
		) {
			items(row.tiles, key = { it.key }) { tile ->
				val key = "${row.key}/${tile.key}"
				TileCard(
					tile = tile,
					onClick = { onSelect(key, tile) },
					modifier = Modifier
						.then(if (key == returnKey) Modifier.focusRequester(returnFocus) else Modifier)
						.then(if (firstItemFocus != null && tile == row.tiles.first()) Modifier.focusRequester(firstItemFocus) else Modifier),
				)
			}
		}
	}
}

/** A genre (with a backdrop) or a network/studio (name on a tinted card). */
@Composable
private fun TileCard(tile: SeerrTile, onClick: () -> Unit, modifier: Modifier = Modifier) {
	val colors = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme
	val cardShape = org.jellyfin.androidtv.ui.composable.item.rememberCardShape()
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()

	Box(
		modifier
			.size(TileWidth, TileHeight)
			.clip(cardShape)
			.background(colors.onBackground.copy(alpha = 0.1f), cardShape)
			.then(if (focused) Modifier.border(2.dp, colors.onBackground, cardShape) else Modifier)
			.clickable(interactionSource = interaction, indication = null, onClick = onClick)
			.focusable(interactionSource = interaction),
		contentAlignment = Alignment.Center,
	) {
		val image = seerrImageUrl(tile.imagePath, "w780")
		if (image != null) {
			SeerrImage(url = image, contentDescription = null, shape = cardShape, modifier = Modifier.fillMaxSize())
			Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (focused) 0.35f else 0.55f)))
		}
		Text(
			tile.name,
			color = if (image != null) Color.White else colors.onBackground,
			fontSize = 18.sp,
			fontWeight = FontWeight.Bold,
			maxLines = 2,
			textAlign = androidx.compose.ui.text.style.TextAlign.Center,
			modifier = Modifier.padding(12.dp),
		)
	}
}

@Composable
internal fun PosterCard(
	item: SeerrResult,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val cardShape = org.jellyfin.androidtv.ui.composable.item.rememberCardShape()
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	val ringAlpha by androidx.compose.animation.core.animateFloatAsState(
		targetValue = if (focused) 1f else 0f,
		animationSpec = androidx.compose.animation.core.tween(120),
		label = "poster-focus-ring",
	)

	Column(modifier.width(PosterWidth)) {
		Box(
			Modifier
				.size(PosterWidth, PosterHeight)
				.clip(cardShape)
				.background(org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.08f), cardShape)
				.then(if (ringAlpha > 0f) Modifier.border(2.dp, org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground.copy(alpha = ringAlpha), cardShape) else Modifier)
				.clickable(interactionSource = interaction, indication = null, onClick = onClick)
				.focusable(interactionSource = interaction)
		) {
			val poster = seerrImageUrl(item.posterPath, "w342")
			if (poster != null) {
				SeerrImage(
					url = poster,
					contentDescription = item.displayTitle,
					shape = cardShape,
					modifier = Modifier.fillMaxSize(),
				)
			} else {
				Text(
					item.displayTitle,
					color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground,
					modifier = Modifier.align(Alignment.Center).padding(8.dp),
				)
			}
			StatusBadge(item.status, Modifier.align(Alignment.TopEnd).padding(6.dp))
		}
		Text(
			text = listOfNotNull(item.displayTitle, item.year).joinToString(" · "),
			color = if (focused) org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground else org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.7f),
			fontSize = 12.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp),
		)
	}
}

@Composable
fun StatusBadge(status: SeerrMediaStatus, modifier: Modifier = Modifier, label4k: Boolean = false) {
	val (label, color) = when (status) {
		SeerrMediaStatus.AVAILABLE -> R.string.seerr_status_available to org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.success
		SeerrMediaStatus.PARTIALLY_AVAILABLE -> R.string.seerr_status_partial to org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.successDim
		SeerrMediaStatus.PENDING -> R.string.seerr_status_requested to org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.warning
		SeerrMediaStatus.PROCESSING -> R.string.seerr_status_processing to org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.info
		else -> return
	}
	Text(
		if (label4k) "4K · " + stringResource(label) else stringResource(label),
		color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground,
		fontSize = 10.sp,
		fontWeight = FontWeight.SemiBold,
		modifier = modifier
			.background(color.copy(alpha = 0.92f), RoundedCornerShape(50))
			.padding(horizontal = 8.dp, vertical = 2.dp),
	)
}

@Composable
private fun DiscoverSkeleton() = org.jellyfin.androidtv.ui.composable.SkeletonGroup {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(start = 48.dp, end = 48.dp, top = 27.dp),
		verticalArrangement = Arrangement.spacedBy(28.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			org.jellyfin.androidtv.ui.composable.SkeletonLine(width = 160.dp, height = 28.dp)
			org.jellyfin.androidtv.ui.composable.SkeletonBox(
				Modifier.padding(start = 24.dp).size(420.dp, 48.dp),
				RoundedCornerShape(percent = 30),
			)
		}
		repeat(3) {
			org.jellyfin.androidtv.ui.composable.SkeletonPosterRow(cardWidth = PosterWidth, cardHeight = PosterHeight)
		}
	}
}

@Composable
private fun SetupNeeded(onOpenSettings: () -> Unit) {
	val focus = remember { FocusRequester() }
	LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
	Centered {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			Text(stringResource(R.string.seerr_setup_needed), color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold)
			Text(
				stringResource(R.string.seerr_setup_needed_caption),
				color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.7f),
				modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
			)
			Button(onClick = onOpenSettings, modifier = Modifier.focusRequester(focus)) {
				Text(stringResource(R.string.seerr_open_settings))
			}
		}
	}
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Scrims over backdrops so text stays readable, tinted with the theme background. */
@Composable
private fun backdropScrims(): Pair<Brush, Brush> {
	val bg = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.background
	return Brush.horizontalGradient(
		0f to bg.copy(alpha = 0.95f),
		0.55f to bg.copy(alpha = 0.7f),
		1f to bg.copy(alpha = 0.2f),
	) to Brush.verticalGradient(
		0.5f to Color.Transparent,
		1f to bg,
	)
}

@Composable
internal fun FullBleedBackdrop(path: String?) {
	val url = seerrImageUrl(path, "w1280") ?: return
	Box(Modifier.fillMaxSize()) {
		SeerrImage(url = url, contentDescription = null, crossfadeMillis = 400, modifier = Modifier.fillMaxSize())
		val (side, bottom) = backdropScrims()
		Box(Modifier.fillMaxSize().background(side))
		Box(Modifier.fillMaxSize().background(bottom))
	}
}

