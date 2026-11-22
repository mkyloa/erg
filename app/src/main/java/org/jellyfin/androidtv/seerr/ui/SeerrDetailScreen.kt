package org.jellyfin.androidtv.seerr.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.seerr.SeerrDetails
import org.jellyfin.androidtv.seerr.SeerrMediaStatus
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import java.util.Locale

@Composable
fun SeerrDetailScreen(
	state: SeerrDetailState,
	viewModel: SeerrDiscoverViewModel,
	onOpenLibraryItem: (String) -> Unit,
) {
	Box(
		Modifier
			.fillMaxSize()
			.background(org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.background)
	) {
		androidx.compose.animation.AnimatedContent(
			targetState = state,
			contentKey = { it::class },
			transitionSpec = {
				androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)) togetherWith
					androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300))
			},
			label = "seerr-detail-state",
		) { current ->
		when (current) {
			SeerrDetailState.Loading -> org.jellyfin.androidtv.ui.composable.SkeletonGroup {
				org.jellyfin.androidtv.ui.composable.SkeletonHero(
					modifier = Modifier.fillMaxSize(),
					shape = androidx.compose.ui.graphics.RectangleShape,
				)
			}
			is SeerrDetailState.Error -> Centered {
				Text(stringResource(R.string.seerr_load_failed, current.message), color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground)
			}

			is SeerrDetailState.Content -> DetailContent(current, viewModel, onOpenLibraryItem)
		}
		}
	}
}

@Composable
private fun DetailContent(
	state: SeerrDetailState.Content,
	viewModel: SeerrDiscoverViewModel,
	onOpenLibraryItem: (String) -> Unit,
) {
	val details = state.details
	var choosingSeasons by remember(details.id) { mutableStateOf(false) }
	val primaryFocus = remember { FocusRequester() }

	val sentMessage = stringResource(R.string.seerr_request_sent)
	val failedTemplate = stringResource(R.string.seerr_request_failed, "%s")
	val onFailed: (String) -> String = { failedTemplate.replace("%s", it) }

	// Back closes the season picker first, then the title page
	androidx.activity.compose.BackHandler(enabled = choosingSeasons) { choosingSeasons = false }

	LaunchedEffect(details.id, choosingSeasons) {
		if (!choosingSeasons) runCatching { primaryFocus.requestFocus() }
	}

	FullBleedBackdrop(details.backdropPath)

	Row(
		Modifier
			.fillMaxSize()
			.padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp)
	) {
		Column(
			Modifier
				.width(640.dp)
				.fillMaxHeight(),
			verticalArrangement = Arrangement.Center,
		) {
			Text(details.displayTitle, color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground, fontSize = 34.sp, fontWeight = FontWeight.Bold)

			Text(
				metaLine(details),
				color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.85f),
				fontSize = 16.sp,
				modifier = Modifier.padding(top = 8.dp),
			)

			StatusBadge(details.status, Modifier.padding(top = 10.dp))

			details.tagline?.takeIf { it.isNotBlank() }?.let {
				Text(it, color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
			}

			Text(
				details.overview.orEmpty(),
				color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.onBackground,
				fontSize = 15.sp,
				maxLines = 6,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(top = 12.dp),
			)

			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier.padding(top = 24.dp),
			) {
				val libraryId = details.mediaInfo?.jellyfinMediaId?.takeIf { details.status.isAvailable }
				var primaryAssigned = false
				fun Modifier.primary(): Modifier = if (!primaryAssigned) {
					primaryAssigned = true
					focusRequester(primaryFocus)
				} else this

				if (libraryId != null) {
					Button(onClick = { onOpenLibraryItem(libraryId) }, modifier = Modifier.primary()) {
						Text(stringResource(R.string.seerr_open_in_library))
					}
				}

				when {
					state.requesting -> Button(onClick = {}, enabled = false, modifier = Modifier.primary()) {
						Text(stringResource(R.string.seerr_requesting))
					}

					state.isMovie && details.status.isRequestable -> Button(
						onClick = { viewModel.request(null, sentMessage, onFailed) },
						modifier = Modifier.primary(),
					) { Text(stringResource(R.string.seerr_request)) }

					!state.isMovie && details.requestableSeasons.any { details.seasonStatus(it.seasonNumber).isRequestable } -> {
						val open = details.requestableSeasons.filter { details.seasonStatus(it.seasonNumber).isRequestable }
						Button(
							onClick = { viewModel.request(open.map { it.seasonNumber }, sentMessage, onFailed) },
							modifier = Modifier.primary(),
						) { Text(stringResource(R.string.seerr_request_all_seasons)) }
						if (details.requestableSeasons.size > 1) {
							Button(onClick = { choosingSeasons = true }) {
								Text(stringResource(R.string.seerr_request_choose_seasons))
							}
						}
					}
				}

				if (!primaryAssigned) {
					// Nothing actionable: keep focus on the screen so Back works and the user isn't lost
					Button(onClick = { viewModel.closeDetail() }, modifier = Modifier.primary()) {
						Text(stringResource(R.string.seerr_back))
					}
				}
			}

			state.message?.let {
				Text(
					it,
					color = if (state.messageIsError) Color(0xFFF87171) else Color(0xFF4ADE80),
					fontSize = 14.sp,
					modifier = Modifier.padding(top = 14.dp),
				)
			}
		}

		// Season picker slides in from the right
		androidx.compose.animation.AnimatedVisibility(
			visible = choosingSeasons && !state.isMovie,
			enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
				androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(250)) { it / 4 },
			exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
		) {
			SeasonPicker(
				details = details,
				onRequest = { seasons ->
					choosingSeasons = false
					viewModel.request(seasons, sentMessage, onFailed)
				},
				modifier = Modifier
					.padding(start = 48.dp)
					.fillMaxHeight()
					.width(420.dp),
			)
		}
	}
}

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
			.background(org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.background.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
			.padding(12.dp),
		contentPadding = PaddingValues(vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		items(details.requestableSeasons, key = { it.seasonNumber }) { season ->
			val status = details.seasonStatus(season.seasonNumber)
			val canPick = status.isRequestable
			val isFirst = season == details.requestableSeasons.firstOrNull { details.seasonStatus(it.seasonNumber).isRequestable }
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
	details.voteAverage?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) },
	details.genres.take(3).joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
).joinToString("  •  ")
