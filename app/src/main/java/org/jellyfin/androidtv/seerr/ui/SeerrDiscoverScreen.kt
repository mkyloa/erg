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
import androidx.compose.ui.text.input.ImeAction
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

private val PosterWidth = 132.dp
private val PosterHeight = 198.dp
private val CardShape = RoundedCornerShape(8.dp)

@Composable
fun SeerrDiscoverScreen(
	viewModel: SeerrDiscoverViewModel,
	onOpenSettings: () -> Unit,
	onOpenLibraryItem: (String) -> Unit,
) {
	val state by viewModel.state.collectAsState()
	val searchResults by viewModel.searchResults.collectAsState()
	val detail by viewModel.detail.collectAsState()
	val searching by viewModel.searching.collectAsState()

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
				rows = current.rows,
				searchResults = searchResults,
				searching = searching,
				onSearch = viewModel::search,
				onSelect = viewModel::openDetail,
			)
		}
		}

		if (detail != null) BackHandler { viewModel.closeDetail() }

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
					state = detailState,
					viewModel = viewModel,
					onOpenLibraryItem = onOpenLibraryItem,
				)
			}
		}
	}
}

@Composable
private fun DiscoverContent(
	rows: List<SeerrRow>,
	searchResults: List<SeerrResult>?,
	searching: Boolean,
	onSearch: (String) -> Unit,
	onSelect: (SeerrResult) -> Unit,
) {
	var query by rememberSaveable { mutableStateOf("") }
	val firstRowFocus = remember { FocusRequester() }

	LaunchedEffect(Unit) { runCatching { firstRowFocus.requestFocus() } }

	LazyColumn(
		contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 48.dp),
		verticalArrangement = Arrangement.spacedBy(20.dp),
		modifier = Modifier.fillMaxSize(),
	) {
		item(key = "header") {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					stringResource(R.string.seerr_discover),
					color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground,
					fontSize = 28.sp,
					fontWeight = FontWeight.Bold,
					modifier = Modifier.padding(end = 24.dp),
				)
				SeerrTextField(
					value = query,
					onValueChange = { query = it; onSearch(it) },
					placeholder = stringResource(R.string.seerr_search_hint),
					icon = painterResource(R.drawable.ic_search),
					imeAction = ImeAction.Search,
					onSubmit = { onSearch(query) },
					modifier = Modifier.width(420.dp),
				)
			}
		}

		if (searching && searchResults == null) {
			item(key = "search-loading") {
				org.jellyfin.androidtv.ui.composable.SkeletonPosterRow(cardWidth = PosterWidth, cardHeight = PosterHeight)
			}
		}

		if (searchResults != null) {
			item(key = "search") {
				if (searchResults.isEmpty()) {
					Text(stringResource(R.string.seerr_no_results), color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.7f))
				} else {
					PosterRow(
						title = stringResource(R.string.seerr_row_search_results),
						items = searchResults,
						onSelect = onSelect,
					)
				}
			}
		}

		rows.forEachIndexed { index, row ->
			item(key = row.key) {
				PosterRow(
					title = stringResource(row.title),
					items = row.items,
					onSelect = onSelect,
					firstItemFocus = if (index == 0 && searchResults == null) firstRowFocus else null,
				)
			}
		}
	}
}

@Composable
private fun PosterRow(
	title: String,
	items: List<SeerrResult>,
	onSelect: (SeerrResult) -> Unit,
	firstItemFocus: FocusRequester? = null,
) {
	Column {
		Text(title, color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(14.dp),
			contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
		) {
			items(items, key = { "${it.mediaType}-${it.id}" }) { item ->
				PosterCard(
					item = item,
					onClick = { onSelect(item) },
					modifier = if (firstItemFocus != null && item == items.first()) Modifier.focusRequester(firstItemFocus) else Modifier,
				)
			}
		}
	}
}

@Composable
private fun PosterCard(
	item: SeerrResult,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
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
				.clip(CardShape)
				.background(org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.08f), CardShape)
				.then(if (ringAlpha > 0f) Modifier.border(2.dp, org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground.copy(alpha = ringAlpha), CardShape) else Modifier)
				.clickable(interactionSource = interaction, indication = null, onClick = onClick)
				.focusable(interactionSource = interaction)
		) {
			val poster = seerrImageUrl(item.posterPath, "w342")
			if (poster != null) {
				SeerrImage(
					url = poster,
					contentDescription = item.displayTitle,
					shape = CardShape,
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
fun StatusBadge(status: SeerrMediaStatus, modifier: Modifier = Modifier) {
	val (label, color) = when (status) {
		SeerrMediaStatus.AVAILABLE -> R.string.seerr_status_available to Color(0xFF22C55E)
		SeerrMediaStatus.PARTIALLY_AVAILABLE -> R.string.seerr_status_partial to Color(0xFF16A34A)
		SeerrMediaStatus.PENDING -> R.string.seerr_status_requested to Color(0xFFF59E0B)
		SeerrMediaStatus.PROCESSING -> R.string.seerr_status_processing to Color(0xFF6366F1)
		else -> return
	}
	Text(
		stringResource(label),
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

