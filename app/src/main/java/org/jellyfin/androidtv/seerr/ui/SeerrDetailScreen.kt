package org.jellyfin.androidtv.seerr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateUtils
import java.time.Instant
import org.jellyfin.androidtv.seerr.SeerrMediaRequest
import org.jellyfin.androidtv.seerr.SeerrRequestOptions
import org.jellyfin.androidtv.seerr.SeerrRequestStatus
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.form.RadioButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.seerr.SeerrCastMember
import org.jellyfin.androidtv.seerr.SeerrDetails
import org.jellyfin.androidtv.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.seerr.SeerrPerson
import org.jellyfin.androidtv.seerr.SeerrRatings
import org.jellyfin.androidtv.seerr.SeerrResult
import org.jellyfin.androidtv.seerr.seerrImageUrl
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.composable.SkeletonGroup
import org.jellyfin.androidtv.ui.composable.SkeletonHero
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Callbacks shared by title and person pages. */
class SeerrPageActions(
	val viewModel: SeerrDiscoverViewModel,
	val onOpenLibraryItem: (String) -> Unit,
	val onPlayTrailer: (String) -> Unit,
	/** Same as the Back key: closes the top page, or leaves Discover when it was opened for one title. */
	val onBack: () -> Unit,
)

@Composable
fun SeerrDetailScreen(
	entry: SeerrDetailEntry,
	liveIds: Set<Int>,
	trapFocus: Boolean,
	actions: SeerrPageActions,
) {
	// Each page keeps its scroll position and focused card while pages are stacked above it
	val pageStates = rememberSaveableStateHolder()
	val seenIds = remember { mutableSetOf<Int>() }
	LaunchedEffect(liveIds) {
		(seenIds - liveIds).forEach(pageStates::removeState)
		seenIds.retainAll(liveIds)
	}

	Box(
		Modifier
			.fillMaxSize()
			.background(JellyfinTheme.colorScheme.background)
			// Discover stays composed underneath; don't let the D-pad wander onto hidden posters
			.focusProperties { onExit = { if (trapFocus) cancelFocusChange() } }
			.focusGroup()
	) {
		AnimatedContent(
			targetState = entry,
			contentKey = { it.id to it.state::class },
			transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
			label = "seerr-page",
		) { current ->
			when (val state = current.state) {
				SeerrDetailState.Loading -> {
					// Hold focus while loading so presses don't reach the hidden Discover posters
					val focus = remember { FocusRequester() }
					LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
					Box(Modifier.fillMaxSize().focusRequester(focus).focusable()) {
						SkeletonGroup {
							SkeletonHero(modifier = Modifier.fillMaxSize(), shape = RectangleShape)
						}
					}
				}

				is SeerrDetailState.Error -> Centered {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text(stringResource(R.string.seerr_load_failed, state.message), color = JellyfinTheme.colorScheme.onBackground)
						val focus = remember { FocusRequester() }
						LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
						Button(onClick = actions.onBack, modifier = Modifier.padding(top = 16.dp).focusRequester(focus)) {
							Text(stringResource(R.string.seerr_back))
						}
					}
				}

				is SeerrDetailState.Content -> {
					seenIds += current.id
					pageStates.SaveableStateProvider(current.id) { TitlePage(state, actions) }
				}

				is SeerrDetailState.Person -> {
					seenIds += current.id
					pageStates.SaveableStateProvider(current.id) { PersonPage(state, actions) }
				}

				is SeerrDetailState.Browse -> {
					seenIds += current.id
					pageStates.SaveableStateProvider(current.id) { BrowsePage(state, actions) }
				}

				is SeerrDetailState.Requests -> {
					seenIds += current.id
					pageStates.SaveableStateProvider(current.id) { RequestsPage(state, actions) }
				}
			}
		}
	}
}

// --- Title page ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitlePage(state: SeerrDetailState.Content, actions: SeerrPageActions) {
	val details = state.details
	val viewModel = actions.viewModel
	var choosingSeasons by remember(details.id) { mutableStateOf(false) }
	var choosingOptions by remember(details.id) { mutableStateOf(false) }
	val primaryFocus = remember { FocusRequester() }
	val listState = rememberLazyListState()
	val scope = rememberCoroutineScope()
	// The card a deeper page was opened from, so Back lands on it again
	var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
	val returnFocus = remember { FocusRequester() }
	val select: (String, SeerrResult) -> Unit = { key, item -> lastOpened = key; viewModel.openDetail(item) }

	val sentMessage = stringResource(R.string.seerr_request_sent)
	val failedTemplate = stringResource(R.string.seerr_request_failed, "%s")
	val onFailed: (String) -> String = { failedTemplate.replace("%s", it) }

	// Back closes a side panel first, then the title page
	BackHandler(enabled = choosingSeasons || choosingOptions) { choosingSeasons = false; choosingOptions = false }

	LaunchedEffect(details.id, choosingSeasons, choosingOptions) {
		if (choosingSeasons || choosingOptions) return@LaunchedEffect
		withFrameNanos { }
		val returned = lastOpened != null && runCatching { returnFocus.requestFocus() }.isSuccess
		if (!returned) runCatching { primaryFocus.requestFocus() }
	}

	// A finished request swaps the Request button for a status, taking focus with it
	var wasRequesting by remember { mutableStateOf(state.requesting) }
	LaunchedEffect(state.requesting) {
		if (wasRequesting && !state.requesting) {
			withFrameNanos { }
			runCatching { primaryFocus.requestFocus() }
		}
		wasRequesting = state.requesting
	}

	FullBleedBackdrop(details.backdropPath)

	// Rows scroll up over the backdrop; darken it so posters and names stay readable
	val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
	val dim by animateFloatAsState(if (scrolled) 0.85f else 0f, tween(250), label = "seerr-backdrop-dim")
	if (dim > 0f) Box(Modifier.fillMaxSize().background(JellyfinTheme.colorScheme.background.copy(alpha = dim)))

	val heroHeight = LocalConfiguration.current.screenHeightDp.dp - 56.dp

	CompositionLocalProvider(LocalBringIntoViewSpec provides rememberMarginScroll()) {
	LazyColumn(
		state = listState,
		contentPadding = PaddingValues(bottom = 48.dp),
		verticalArrangement = Arrangement.spacedBy(20.dp),
		modifier = Modifier.fillMaxSize(),
	) {
		item(key = "hero") {
			Box(
				Modifier
					.fillMaxWidth()
					.height(heroHeight)
					// Coming back up from the rows shows the whole title block again
					.onFocusChanged { if (it.hasFocus && scrolled) scope.launch { listState.animateScrollToItem(0) } }
					.padding(start = ScreenInset, end = ScreenInset, top = 40.dp),
				contentAlignment = Alignment.CenterStart,
			) {
				TitleHero(
					state = state,
					primaryFocus = primaryFocus,
					onRequest = { seasons, options -> viewModel.request(seasons, options, sentMessage, onFailed) },
					onChooseSeasons = { choosingSeasons = true },
					onChooseOptions = { choosingOptions = true },
					onToggleWatchlist = { viewModel.toggleWatchlist(onFailed) },
					actions = actions,
				)
			}
		}

		if (details.cast.isNotEmpty()) item(key = "cast") {
			CastRow(details.cast, onSelect = { key, person -> lastOpened = key; viewModel.openPerson(person.id) }, returnKey = lastOpened, returnFocus = returnFocus)
		}
		if (state.recommendations.isNotEmpty()) item(key = "recommendations") {
			PosterRow("recommendations", stringResource(R.string.seerr_row_recommended), state.recommendations, select, lastOpened, returnFocus)
		}
		if (state.similar.isNotEmpty()) item(key = "similar") {
			PosterRow("similar", stringResource(R.string.seerr_row_similar), state.similar, select, lastOpened, returnFocus)
		}
	}
	}

	// Season picker slides in from the right
	Box(Modifier.fillMaxSize().padding(top = 48.dp, bottom = 32.dp, end = 48.dp), contentAlignment = Alignment.CenterEnd) {
		AnimatedVisibility(
			visible = choosingSeasons && !state.isMovie,
			enter = fadeIn(tween(200)) + slideInHorizontally(tween(250)) { it / 4 },
			exit = fadeOut(tween(150)),
		) {
			SeasonPicker(
				details = details,
				onRequest = { seasons ->
					choosingSeasons = false
					viewModel.request(seasons, SeerrRequestOptions(), sentMessage, onFailed)
				},
				modifier = Modifier
					.fillMaxHeight()
					.width(420.dp),
			)
		}
		AnimatedVisibility(
			visible = choosingOptions,
			enter = fadeIn(tween(200)) + slideInHorizontally(tween(250)) { it / 4 },
			exit = fadeOut(tween(150)),
		) {
			RequestOptionsPanel(
				state = state,
				onLoadProfiles = viewModel::loadProfiles,
				onRequest = { options ->
					choosingOptions = false
					viewModel.request(null, options, sentMessage, onFailed)
				},
				modifier = Modifier
					.fillMaxHeight()
					.width(460.dp),
			)
		}
	}
}

@Composable
private fun TitleHero(
	state: SeerrDetailState.Content,
	primaryFocus: FocusRequester,
	onRequest: (List<Int>?, SeerrRequestOptions) -> Unit,
	onChooseSeasons: () -> Unit,
	onChooseOptions: () -> Unit,
	onToggleWatchlist: () -> Unit,
	actions: SeerrPageActions,
) {
	val details = state.details
	val colors = JellyfinTheme.colorScheme

	Column(Modifier.width(680.dp)) {
		Text(details.displayTitle, color = colors.onBackground, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)

		Text(
			metaLine(details),
			color = colors.onBackground.copy(alpha = 0.85f),
			fontSize = 16.sp,
			modifier = Modifier.padding(top = 8.dp),
		)

		details.makers.takeIf { it.isNotEmpty() }?.let { names ->
			Text(
				stringResource(if (state.isMovie) R.string.seerr_directed_by else R.string.seerr_created_by, names.joinToString(", ")),
				color = colors.onBackground.copy(alpha = 0.7f),
				fontSize = 14.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(top = 4.dp),
			)
		}

		RatingChips(details.voteAverage, state.ratings, Modifier.padding(top = 12.dp))

		if (state.isMovie) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			StatusBadge(details.status)
			// The 4K copy is tracked separately; show it once there's something to say
			details.status(is4k = true).takeUnless { it.isRequestable }?.let { StatusBadge(it, label4k = true) }
		}
		else SeasonStatusChips(details, Modifier.padding(top = 10.dp))

		details.tagline?.takeIf { it.isNotBlank() }?.let {
			Text(it, color = colors.onBackground.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
		}

		Text(
			details.overview.orEmpty(),
			color = colors.onBackground,
			fontSize = 15.sp,
			maxLines = 4,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 10.dp),
		)

		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
			modifier = Modifier.padding(top = 20.dp),
		) {
			val libraryId = details.mediaInfo?.jellyfinMediaId?.takeIf { details.status.isAvailable }
			var primaryAssigned = false
			fun Modifier.primary(): Modifier = if (!primaryAssigned) {
				primaryAssigned = true
				focusRequester(primaryFocus)
			} else this

			if (libraryId != null) {
				Button(onClick = { actions.onOpenLibraryItem(libraryId) }, modifier = Modifier.primary()) {
					Text(stringResource(R.string.seerr_open_in_library))
				}
			}

			val openSeasons = details.missingSeasons()
			when {
				// Stays enabled (a no-op) so the focus it had doesn't drop to the page root
				state.requesting -> Button(onClick = {}, modifier = Modifier.primary()) {
					Text(stringResource(R.string.seerr_requesting))
				}

				state.isMovie && details.status.isRequestable -> Button(
					onClick = { onRequest(null, SeerrRequestOptions()) },
					modifier = Modifier.primary(),
				) { Text(stringResource(R.string.seerr_request)) }

				!state.isMovie && openSeasons.isNotEmpty() -> {
					// Only the missing seasons; a show that's partly there asks for the rest
					val allMissing = openSeasons.size == details.requestableSeasons.size
					Button(
						onClick = { onRequest(openSeasons.map { it.seasonNumber }, SeerrRequestOptions()) },
						modifier = Modifier.primary(),
					) {
						Text(
							if (allMissing) stringResource(R.string.seerr_request_all_seasons)
							else pluralStringResource(R.plurals.seerr_request_missing_seasons, openSeasons.size, openSeasons.size)
						)
					}
					if (openSeasons.size > 1) {
						Button(onClick = onChooseSeasons) {
							Text(stringResource(R.string.seerr_request_choose_seasons))
						}
					}
				}
			}

			// 4K is its own copy with its own status
			if (state.can4k && !state.requesting) {
				val open4k = if (state.isMovie) details.status(is4k = true).isRequestable else details.missingSeasons(is4k = true).isNotEmpty()
				if (open4k) Button(onClick = { onRequest(null, SeerrRequestOptions(is4k = true)) }) {
					Text(stringResource(R.string.seerr_request_4k))
				}
			}

			if (state.canChooseOptions && !state.requesting) {
				val anyOpen = if (state.isMovie) details.status.isRequestable || details.status(true).isRequestable
				else details.missingSeasons().isNotEmpty() || details.missingSeasons(true).isNotEmpty()
				if (anyOpen) Button(onClick = onChooseOptions) {
					Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(18.dp))
					Text(stringResource(R.string.seerr_request_options), modifier = Modifier.padding(start = 8.dp))
				}
			}

			details.trailer?.key?.let { key ->
				Button(onClick = { actions.onPlayTrailer(key) }, modifier = Modifier.primary()) {
					Icon(painterResource(R.drawable.ic_trailer), contentDescription = null, modifier = Modifier.size(18.dp))
					Text(stringResource(R.string.seerr_trailer), modifier = Modifier.padding(start = 8.dp))
				}
			}

			// Servers without Seerr's own watchlist don't send the flag
			details.onUserWatchlist?.let { onList ->
				Button(onClick = { if (!state.watchlistBusy) onToggleWatchlist() }, modifier = Modifier.primary()) {
					Icon(painterResource(if (onList) R.drawable.ic_check else R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
					Text(
						stringResource(if (onList) R.string.seerr_on_watchlist else R.string.seerr_add_to_watchlist),
						modifier = Modifier.padding(start = 8.dp),
					)
				}
			}

			if (!primaryAssigned) {
				// Nothing actionable: keep focus on the screen so Back works and the user isn't lost
				Button(onClick = { actions.viewModel.closeDetail() }, modifier = Modifier.primary()) {
					Text(stringResource(R.string.seerr_back))
				}
			}
		}

		state.message?.let {
			Text(
				it,
				color = if (state.messageIsError) colors.error else colors.success,
				fontSize = 14.sp,
				modifier = Modifier.padding(top = 12.dp),
			)
		}
	}
}

/** TMDB, Rotten Tomatoes and IMDb scores, whichever exist. */
@Composable
private fun RatingChips(tmdb: Double?, ratings: SeerrRatings?, modifier: Modifier = Modifier) {
	val chips = listOfNotNull(
		ratings?.rt?.criticsScore?.let { stringResource(R.string.seerr_rating_critics) to "${it.toInt()}%" },
		ratings?.rt?.audienceScore?.let { stringResource(R.string.seerr_rating_audience) to "${it.toInt()}%" },
		ratings?.imdb?.criticsScore?.takeIf { it > 0 }?.let { "IMDb" to String.format(Locale.US, "%.1f", it) },
		tmdb?.takeIf { it > 0 }?.let { "TMDB" to String.format(Locale.US, "%.1f", it) },
	)
	if (chips.isEmpty()) return
	Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		chips.forEach { (label, value) -> InfoChip(label, value) }
	}
}

@Composable
private fun InfoChip(label: String, value: String, accent: Color? = null) {
	val colors = JellyfinTheme.colorScheme
	Row(
		Modifier
			.background(accent?.copy(alpha = 0.9f) ?: colors.onBackground.copy(alpha = 0.12f), RoundedCornerShape(50))
			.padding(horizontal = 10.dp, vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(label, color = colors.onBackground.copy(alpha = if (accent != null || value.isEmpty()) 0.95f else 0.65f), fontSize = 12.sp, fontWeight = if (value.isEmpty()) FontWeight.SemiBold else null)
		if (value.isNotEmpty()) Text(value, color = colors.onBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
	}
}

/** "S1–3 Available · S4 Requested · S5 Not requested", grouped into runs of the same status. */
@Composable
private fun SeasonStatusChips(details: SeerrDetails, modifier: Modifier = Modifier) {
	val seasons = details.requestableSeasons.map { it.seasonNumber to details.seasonStatus(it.seasonNumber) }
	// Nothing known about any season: the request buttons say it all
	if (seasons.none { (_, status) -> !status.isRequestable }) return

	val runs = mutableListOf<Triple<Int, Int, SeerrMediaStatus>>()
	seasons.forEach { (number, status) ->
		val last = runs.lastOrNull()
		if (last != null && last.third == status && last.second == number - 1) runs[runs.lastIndex] = last.copy(second = number)
		else runs += Triple(number, number, status)
	}

	val colors = JellyfinTheme.colorScheme
	FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		runs.forEach { (first, last, status) ->
			val (label, accent) = when (status) {
				SeerrMediaStatus.AVAILABLE -> R.string.seerr_status_available to colors.success
				SeerrMediaStatus.PARTIALLY_AVAILABLE -> R.string.seerr_status_partial to colors.successDim
				SeerrMediaStatus.PENDING -> R.string.seerr_status_requested to colors.warning
				SeerrMediaStatus.PROCESSING -> R.string.seerr_status_processing to colors.info
				else -> R.string.seerr_status_not_requested to null
			}
			InfoChip(if (first == last) "S$first" else "S$first–$last", stringResource(label), accent)
		}
	}
}

/**
 * Leaves visible elements where they are and scrolls hidden ones in with [margin] to spare, so a
 * cast photo's name and a row's title show too. On TV, Compose otherwise moves every focused element
 * to a pivot a third of the way down, which pushes the title block off the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberMarginScroll(margin: androidx.compose.ui.unit.Dp = 72.dp): BringIntoViewSpec {
	val marginPx = with(LocalDensity.current) { margin.toPx() }
	return remember(marginPx) {
		object : BringIntoViewSpec {
			override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
				val trailing = offset + size
				return when {
					offset >= 0 && trailing <= containerSize -> 0f
					offset < 0 && trailing > containerSize -> 0f
					offset < 0 -> offset - marginPx
					else -> (trailing - containerSize + marginPx).coerceAtMost(offset)
				}
			}
		}
	}
}

// --- Cast ---------------------------------------------------------------------------------

private val CastPhotoSize = 96.dp

@Composable
private fun CastRow(
	cast: List<SeerrCastMember>,
	onSelect: (String, SeerrCastMember) -> Unit,
	returnKey: String?,
	returnFocus: FocusRequester,
) {
	Column {
		RowTitle(stringResource(R.string.seerr_row_cast))
		LazyRow(
			horizontalArrangement = Arrangement.spacedBy(14.dp),
			contentPadding = PaddingValues(vertical = 4.dp, horizontal = ScreenInset),
		) {
			items(cast, key = { it.id }) { person ->
				val key = "cast/${person.id}"
				CastCard(
					person = person,
					onClick = { onSelect(key, person) },
					modifier = if (key == returnKey) Modifier.focusRequester(returnFocus) else Modifier,
				)
			}
		}
	}
}

@Composable
private fun CastCard(person: SeerrCastMember, onClick: () -> Unit, modifier: Modifier = Modifier) {
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	val colors = JellyfinTheme.colorScheme

	Column(modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
		Box(
			Modifier
				.size(CastPhotoSize)
				.clip(CircleShape)
				.background(colors.onBackground.copy(alpha = 0.1f), CircleShape)
				.then(if (focused) Modifier.border(2.dp, colors.onBackground, CircleShape) else Modifier)
				.clickable(interactionSource = interaction, indication = null, onClick = onClick)
				.focusable(interactionSource = interaction),
			contentAlignment = Alignment.Center,
		) {
			val photo = seerrImageUrl(person.profilePath, "w185")
			if (photo != null) SeerrImage(url = photo, contentDescription = person.name, shape = CircleShape, modifier = Modifier.fillMaxSize())
			else Text(initials(person.name), color = colors.onBackground.copy(alpha = 0.7f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
		}
		Text(
			person.name.orEmpty(),
			color = if (focused) colors.onBackground else colors.onBackground.copy(alpha = 0.85f),
			fontSize = 12.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			textAlign = TextAlign.Center,
			modifier = Modifier.padding(top = 8.dp),
		)
		person.character?.takeIf { it.isNotBlank() }?.let {
			Text(it, color = colors.onBackground.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
		}
	}
}

private fun initials(name: String?) = name.orEmpty().split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }

// --- Person page --------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonPage(state: SeerrDetailState.Person, actions: SeerrPageActions) {
	val person = state.person
	val colors = JellyfinTheme.colorScheme
	val listState = rememberLazyListState()
	var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
	val returnFocus = remember { FocusRequester() }
	val firstFocus = remember { FocusRequester() }
	val select: (String, SeerrResult) -> Unit = { key, item -> lastOpened = key; actions.viewModel.openDetail(item) }

	LaunchedEffect(person.id) {
		withFrameNanos { }
		val returned = lastOpened != null && runCatching { returnFocus.requestFocus() }.isSuccess
		if (!returned) runCatching { firstFocus.requestFocus() }
	}

	val rows = listOf(
		Triple("known_for", R.string.seerr_row_known_for, state.knownFor),
		Triple("acting", R.string.seerr_row_acting, state.acting),
		Triple("crew", R.string.seerr_row_crew, state.crew),
	).filter { it.third.isNotEmpty() }

	CompositionLocalProvider(LocalBringIntoViewSpec provides rememberMarginScroll()) {
	LazyColumn(
		state = listState,
		contentPadding = PaddingValues(top = 40.dp, bottom = 48.dp),
		verticalArrangement = Arrangement.spacedBy(20.dp),
		modifier = Modifier.fillMaxSize(),
	) {
		item(key = "header") {
			Row(Modifier.padding(horizontal = ScreenInset)) {
				Box(
					Modifier
						.size(140.dp, 210.dp)
						.clip(RoundedCornerShape(12.dp))
						.background(colors.onBackground.copy(alpha = 0.1f)),
					contentAlignment = Alignment.Center,
				) {
					val photo = seerrImageUrl(person.profilePath, "w342")
					if (photo != null) SeerrImage(url = photo, contentDescription = person.name, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxSize())
					else Text(initials(person.name), color = colors.onBackground.copy(alpha = 0.7f), fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
				}
				Column(Modifier.padding(start = 28.dp).width(720.dp)) {
					Text(person.name.orEmpty(), color = colors.onBackground, fontSize = 34.sp, fontWeight = FontWeight.Bold)
					personLine(person)?.let {
						Text(it, color = colors.onBackground.copy(alpha = 0.75f), fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
					}
					person.biography?.takeIf { it.isNotBlank() }?.let {
						Text(it, color = colors.onBackground, fontSize = 14.sp, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
					}
				}
			}
		}

		rows.forEachIndexed { index, (key, title, items) ->
			item(key = key) {
				PosterRow(key, stringResource(title), items, select, lastOpened, returnFocus, firstItemFocus = if (index == 0) firstFocus else null)
			}
		}

		if (rows.isEmpty()) item(key = "empty") {
			// Nothing to browse: keep something focusable so Back and focus still work
			Box(Modifier.padding(horizontal = ScreenInset)) {
				Button(onClick = actions.onBack, modifier = Modifier.focusRequester(firstFocus)) {
					Text(stringResource(R.string.seerr_back))
				}
			}
		}
	}
	}
}

@Composable
private fun personLine(person: SeerrPerson): String? {
	val born = person.birthday?.let(::formatDate)
	val died = person.deathday?.let(::formatDate)
	return listOfNotNull(
		person.knownForDepartment,
		born?.let {
			if (person.placeOfBirth.isNullOrBlank()) stringResource(R.string.seerr_born, it)
			else stringResource(R.string.seerr_born_in, it, person.placeOfBirth)
		},
		died?.let { stringResource(R.string.seerr_died, it) },
	).joinToString("  •  ").ifBlank { null }
}

private fun formatDate(value: String): String? = runCatching {
	LocalDate.parse(value.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrNull()

// --- Request options ----------------------------------------------------------------------

/** 4K or standard, which Radarr/Sonarr server, and which quality profile. */
@Composable
private fun RequestOptionsPanel(
	state: SeerrDetailState.Content,
	onLoadProfiles: (Int) -> Unit,
	onRequest: (SeerrRequestOptions) -> Unit,
	modifier: Modifier = Modifier,
) {
	val details = state.details
	val standardOpen = if (state.isMovie) details.status.isRequestable else details.missingSeasons().isNotEmpty()
	val fourKOpen = state.can4k && (if (state.isMovie) details.status(true).isRequestable else details.missingSeasons(true).isNotEmpty())
	var is4k by remember(details.id) { mutableStateOf(!standardOpen && fourKOpen) }
	val servers = state.services.filter { it.is4k == is4k }
	var serverId by remember(details.id, is4k) { mutableStateOf(servers.firstOrNull { it.isDefault }?.id ?: servers.firstOrNull()?.id) }
	val server = servers.firstOrNull { it.id == serverId }
	LaunchedEffect(serverId) { serverId?.let(onLoadProfiles) }
	val profiles = serverId?.let { state.profiles[it] }
	var profileId by remember(details.id, serverId) { mutableStateOf<Int?>(null) }
	val chosenProfile = profileId ?: server?.activeProfileId

	// Focus starts on the current choice of the first question; profiles arrive a moment later
	val firstFocus = remember { FocusRequester() }
	val focusGroup = when {
		standardOpen && fourKOpen -> "version"
		servers.size > 1 -> "server"
		profiles != null -> "profile"
		else -> "request"
	}
	LaunchedEffect(focusGroup) {
		withFrameNanos { }
		runCatching { firstFocus.requestFocus() }
	}
	fun Modifier.initial(group: String, checked: Boolean): Modifier = if (group == focusGroup && checked) focusRequester(firstFocus) else this

	LazyColumn(
		modifier = modifier
			.background(JellyfinTheme.colorScheme.background.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
			.padding(12.dp),
		contentPadding = PaddingValues(vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		if (standardOpen && fourKOpen) {
			item(key = "version") { PanelHeading(stringResource(R.string.seerr_option_version)) }
			item(key = "standard") {
				ListButton(
					headingContent = { Text(stringResource(R.string.seerr_option_standard)) },
					trailingContent = { RadioButton(checked = !is4k) },
					onClick = { is4k = false },
					modifier = Modifier.initial("version", !is4k),
				)
			}
			item(key = "4k") {
				ListButton(
					headingContent = { Text("4K") },
					trailingContent = { RadioButton(checked = is4k) },
					onClick = { is4k = true },
					modifier = Modifier.initial("version", is4k),
				)
			}
		}

		if (servers.size > 1) {
			item(key = "servers") { PanelHeading(stringResource(R.string.seerr_option_server)) }
			items(servers, key = { "server-${it.id}" }) { option ->
				ListButton(
					headingContent = { Text(option.name ?: "#${option.id}") },
					trailingContent = { RadioButton(checked = option.id == serverId) },
					onClick = { serverId = option.id },
					modifier = Modifier.initial("server", option.id == serverId),
				)
			}
		}

		item(key = "profiles") { PanelHeading(stringResource(R.string.seerr_option_quality)) }
		when {
			profiles == null -> item(key = "profiles-loading") {
				Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
			}

			else -> items(profiles, key = { "profile-${it.id}" }) { profile ->
				ListButton(
					headingContent = { Text(profile.name ?: "#${profile.id}") },
					trailingContent = { RadioButton(checked = profile.id == chosenProfile) },
					onClick = { profileId = profile.id },
					// No default profile on the server: start on the first one
					modifier = Modifier.initial("profile", profile.id == chosenProfile || (chosenProfile !in profiles.map { it.id } && profile == profiles.first())),
				)
			}
		}

		item(key = "request") {
			Button(
				onClick = { onRequest(SeerrRequestOptions(is4k = is4k, serverId = serverId, profileId = chosenProfile, rootFolder = server?.activeDirectory)) },
				enabled = serverId != null,
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 8.dp)
					.initial("request", true),
			) {
				Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
					Text(stringResource(if (is4k) R.string.seerr_request_4k else R.string.seerr_request))
				}
			}
		}
	}
}

@Composable
private fun PanelHeading(text: String) = Text(
	text,
	color = JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.6f),
	fontSize = 13.sp,
	modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
)

// --- Browse grid --------------------------------------------------------------------------

@Composable
private fun BrowsePage(state: SeerrDetailState.Browse, actions: SeerrPageActions) {
	val gridState = rememberLazyGridState()
	var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
	val returnFocus = remember { FocusRequester() }
	val firstFocus = remember { FocusRequester() }

	LaunchedEffect(state.items.isNotEmpty()) {
		if (state.items.isEmpty()) return@LaunchedEffect
		withFrameNanos { }
		val returned = lastOpened != null && runCatching { returnFocus.requestFocus() }.isSuccess
		if (!returned) runCatching { firstFocus.requestFocus() }
	}

	// Next page a couple of rows before the end
	val nearEnd by remember(state.items.size) {
		derivedStateOf { (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.items.size - 14 }
	}
	LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && state.hasMore) actions.viewModel.loadMoreBrowse() }

	Column(Modifier.fillMaxSize()) {
		Text(
			state.title,
			color = JellyfinTheme.colorScheme.onBackground,
			fontSize = 28.sp,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.padding(start = ScreenInset, top = 27.dp, bottom = 12.dp),
		)
		if (state.items.isEmpty() && state.loading) {
			Centered { CircularProgressIndicator() }
			return@Column
		}
		LazyVerticalGrid(
			state = gridState,
			columns = GridCells.Adaptive(132.dp),
			contentPadding = PaddingValues(start = ScreenInset, end = ScreenInset, top = 4.dp, bottom = 48.dp),
			horizontalArrangement = Arrangement.spacedBy(14.dp),
			verticalArrangement = Arrangement.spacedBy(18.dp),
			modifier = Modifier.fillMaxSize(),
		) {
			gridItems(state.items, key = { "${it.mediaType}-${it.id}" }) { item ->
				val key = "${item.mediaType}-${item.id}"
				PosterCard(
					item = item,
					onClick = { lastOpened = key; actions.viewModel.openDetail(item) },
					modifier = Modifier
						.then(if (key == lastOpened) Modifier.focusRequester(returnFocus) else Modifier)
						.then(if (item == state.items.first()) Modifier.focusRequester(firstFocus) else Modifier),
				)
			}
		}
	}
}

// --- Requests -----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RequestsPage(state: SeerrDetailState.Requests, actions: SeerrPageActions) {
	val colors = JellyfinTheme.colorScheme
	val viewModel = actions.viewModel
	val listState = rememberLazyListState()
	var lastOpened by rememberSaveable { mutableStateOf<Int?>(null) }
	val returnFocus = remember { FocusRequester() }
	val tabFocus = remember { FocusRequester() }
	val firstFocus = remember { FocusRequester() }
	val failedTemplate = stringResource(R.string.seerr_action_failed, "%s")

	LaunchedEffect(state.tab, state.loading && state.items.isEmpty()) {
		withFrameNanos { }
		val returned = lastOpened != null && runCatching { returnFocus.requestFocus() }.isSuccess
		if (!returned) runCatching { if (state.items.isNotEmpty() && !state.canManage) firstFocus.requestFocus() else tabFocus.requestFocus() }
	}

	// Approving or declining on the Pending tab removes the focused row
	var lastCount by remember { mutableStateOf(state.items.size) }
	LaunchedEffect(state.items.size) {
		if (state.items.size < lastCount) {
			withFrameNanos { }
			val moved = state.items.isNotEmpty() && runCatching { firstFocus.requestFocus() }.isSuccess
			if (!moved) runCatching { tabFocus.requestFocus() }
		}
		lastCount = state.items.size
	}

	val nearEnd by remember(state.items.size) {
		derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.items.size - 4 }
	}
	LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && state.hasMore && state.items.isNotEmpty()) viewModel.loadMoreRequests() }

	Column(Modifier.fillMaxSize().padding(top = 27.dp)) {
		Row(Modifier.padding(horizontal = ScreenInset), verticalAlignment = Alignment.CenterVertically) {
			Text(
				stringResource(R.string.seerr_requests),
				color = colors.onBackground,
				fontSize = 28.sp,
				fontWeight = FontWeight.Bold,
				modifier = Modifier.padding(end = 24.dp),
			)
			if (state.canManage) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					listOf(SeerrRequestsTab.PENDING, SeerrRequestsTab.MINE, SeerrRequestsTab.ALL).forEach { tab ->
						val selected = tab == state.tab
						Button(
							onClick = { viewModel.selectRequestsTab(tab) },
							colors = if (selected) ButtonDefaults.colors(containerColor = colors.onBackground.copy(alpha = 0.28f), contentColor = colors.onBackground)
							else ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = colors.onBackground.copy(alpha = 0.6f)),
							modifier = if (selected) Modifier.focusRequester(tabFocus) else Modifier,
						) { Text(stringResource(tab.title)) }
					}
				}
			} else {
				// Something to hold focus while the list loads
				Box(Modifier.size(1.dp).focusRequester(tabFocus).focusable())
			}
		}

		state.message?.let {
			Text(it, color = colors.error, fontSize = 14.sp, modifier = Modifier.padding(start = ScreenInset, top = 10.dp))
		}

		when {
			state.items.isEmpty() && state.loading -> Centered { CircularProgressIndicator() }
			state.items.isEmpty() -> Centered {
				Text(stringResource(if (state.tab == SeerrRequestsTab.PENDING) R.string.seerr_requests_none_pending else R.string.seerr_requests_empty), color = colors.onBackground.copy(alpha = 0.7f), fontSize = 16.sp)
			}

			else -> CompositionLocalProvider(LocalBringIntoViewSpec provides rememberMarginScroll()) {
				LazyColumn(
					state = listState,
					contentPadding = PaddingValues(start = ScreenInset, end = ScreenInset, top = 16.dp, bottom = 48.dp),
					verticalArrangement = Arrangement.spacedBy(10.dp),
					modifier = Modifier.fillMaxSize(),
				) {
					items(state.items, key = { it.request.id }) { item ->
						RequestRow(
							item = item,
							showRequester = !state.tab.mineOnly,
							canManage = state.canManage,
							busy = item.request.id in state.busy,
							onOpen = { lastOpened = item.request.id; viewModel.openDetail(item.toResult()) },
							onApprove = { approve -> viewModel.setRequestApproved(item.request.id, approve) { failedTemplate.replace("%s", it) } },
							modifier = Modifier
								.then(if (item.request.id == lastOpened) Modifier.focusRequester(returnFocus) else Modifier)
								.then(if (item == state.items.first()) Modifier.focusRequester(firstFocus) else Modifier),
						)
					}
					if (state.loading) item(key = "loading") {
						Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
					}
				}
			}
		}
	}
}

@Composable
private fun RequestRow(
	item: SeerrRequestItem,
	showRequester: Boolean,
	canManage: Boolean,
	busy: Boolean,
	onOpen: () -> Unit,
	onApprove: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = JellyfinTheme.colorScheme
	val request = item.request
	val interaction = remember { MutableInteractionSource() }
	val focused by interaction.collectIsFocusedAsState()
	val media = request.media
	val mediaStatus = media?.status(request.is4k) ?: SeerrMediaStatus.UNKNOWN
	val progress = media?.downloadProgress(request.is4k)

	Row(verticalAlignment = Alignment.CenterVertically) {
		Row(
			modifier
				.weight(1f)
				.background(colors.onBackground.copy(alpha = if (focused) 0.16f else 0.06f), RoundedCornerShape(12.dp))
				.then(if (focused) Modifier.border(2.dp, colors.onBackground, RoundedCornerShape(12.dp)) else Modifier)
				.clickable(interactionSource = interaction, indication = null, onClick = onOpen)
				.focusable(interactionSource = interaction)
				.padding(10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(
				Modifier
					.size(48.dp, 72.dp)
					.clip(RoundedCornerShape(6.dp))
					.background(colors.onBackground.copy(alpha = 0.1f))
			) {
				seerrImageUrl(item.posterPath, "w154")?.let { SeerrImage(url = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
			}
			Column(Modifier.padding(start = 16.dp).weight(1f)) {
				Text(
					listOfNotNull(item.title, item.year).joinToString(" · "),
					color = colors.onBackground,
					fontSize = 16.sp,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					requestLine(request, showRequester),
					color = colors.onBackground.copy(alpha = 0.65f),
					fontSize = 13.sp,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 2.dp),
				)
				if (progress != null && request.status != SeerrRequestStatus.DECLINED) {
					Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
						Box(
							Modifier
								.width(260.dp)
								.height(6.dp)
								.clip(RoundedCornerShape(50))
								.background(colors.onBackground.copy(alpha = 0.15f))
						) {
							Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(colors.info))
						}
						Text(
							listOfNotNull("${(progress * 100).toInt()}%", media.downloadEta(request.is4k)?.let { etaText(it) }).joinToString(" · "),
							color = colors.onBackground.copy(alpha = 0.75f),
							fontSize = 12.sp,
							modifier = Modifier.padding(start = 10.dp),
						)
					}
				}
			}
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				if (request.is4k) InfoChip("4K", "")
				RequestStatusChip(request.status, mediaStatus, downloading = progress != null)
			}
		}

		if (canManage && request.status == SeerrRequestStatus.PENDING) {
			Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				// Busy rows ignore presses but stay focusable, so focus isn't dropped mid-action
				Button(onClick = { if (!busy) onApprove(true) }) { Text(stringResource(R.string.seerr_approve)) }
				Button(onClick = { if (!busy) onApprove(false) }) { Text(stringResource(R.string.seerr_decline)) }
			}
		}
	}
}

@Composable
private fun RequestStatusChip(requestStatus: Int?, mediaStatus: SeerrMediaStatus, downloading: Boolean) {
	val colors = JellyfinTheme.colorScheme
	val (label, color) = when {
		requestStatus == SeerrRequestStatus.DECLINED -> R.string.seerr_request_declined to colors.error
		requestStatus == SeerrRequestStatus.PENDING -> R.string.seerr_request_pending to colors.warning
		mediaStatus == SeerrMediaStatus.AVAILABLE -> R.string.seerr_status_available to colors.success
		mediaStatus == SeerrMediaStatus.PARTIALLY_AVAILABLE -> R.string.seerr_status_partial to colors.successDim
		downloading -> R.string.seerr_request_downloading to colors.info
		mediaStatus == SeerrMediaStatus.PROCESSING -> R.string.seerr_status_processing to colors.info
		else -> R.string.seerr_request_approved to colors.info
	}
	InfoChip(stringResource(label), "", color)
}

@Composable
private fun requestLine(request: SeerrMediaRequest, showRequester: Boolean): String {
	val context = LocalContext.current
	val whenText = request.createdAt?.let { iso ->
		runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()?.let {
			DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
		}
	}
	val seasons = request.seasons.map { it.seasonNumber }.distinct().sorted().takeIf { it.isNotEmpty() }?.let { numbers ->
		context.resources.getQuantityString(R.plurals.seerr_seasons, numbers.size, seasonRanges(numbers))
	}
	val by = request.requestedBy?.name?.takeIf { showRequester }?.let { stringResource(R.string.seerr_requested_by, it) }
	return listOfNotNull(by, whenText, seasons).joinToString("  •  ")
}

/** 1, 2, 3, 5 → "1–3, 5". */
private fun seasonRanges(numbers: List<Int>): String {
	val runs = mutableListOf<IntRange>()
	numbers.forEach { n ->
		val last = runs.lastOrNull()
		if (last != null && last.last == n - 1) runs[runs.lastIndex] = last.first..n else runs += n..n
	}
	return runs.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
}

@Composable
private fun etaText(iso: String): String? {
	val millis = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return null
	val minutes = ((millis - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
	val text = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
	return stringResource(R.string.seerr_time_left, text)
}

// --- Season picker ------------------------------------------------------------------------

@Composable
private fun SeasonPicker(
	details: SeerrDetails,
	onRequest: (List<Int>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val requestable = details.requestableSeasons.filter { details.seasonStatus(it.seasonNumber).isRequestable }
	var selected by remember(details.id) { mutableStateOf(requestable.map { it.seasonNumber }.toSet()) }
	val firstFocus = remember { FocusRequester() }
	LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

	LazyColumn(
		modifier = modifier
			.background(JellyfinTheme.colorScheme.background.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
			.padding(12.dp),
		contentPadding = PaddingValues(vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		items(details.requestableSeasons, key = { it.seasonNumber }) { season ->
			val status = details.seasonStatus(season.seasonNumber)
			val canPick = status.isRequestable
			val isFirst = season == requestable.firstOrNull()
			ListButton(
				enabled = canPick,
				headingContent = { Text(season.name ?: stringResource(R.string.seerr_season_n, season.seasonNumber)) },
				captionContent = season.episodeCount?.let { { Text(stringResource(R.string.seerr_episodes_n, it)) } },
				trailingContent = {
					if (canPick) Checkbox(checked = season.seasonNumber in selected)
					else StatusBadge(status)
				},
				onClick = {
					selected = if (season.seasonNumber in selected) selected - season.seasonNumber else selected + season.seasonNumber
				},
				modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
			)
		}

		item(key = "request") {
			Button(
				onClick = { onRequest(selected.sorted()) },
				enabled = selected.isNotEmpty(),
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 8.dp),
			) {
				Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
					Text(stringResource(R.string.seerr_request_selected, selected.size))
				}
			}
		}
	}
}

private fun metaLine(details: SeerrDetails): String = listOfNotNull(
	details.year,
	details.runtimeMinutes?.takeIf { it > 0 }?.let { minutes ->
		if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
	},
	details.genres.take(3).joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
).joinToString("  •  ")
