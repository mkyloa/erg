package org.jellyfin.androidtv.seerr.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.seerr.MEDIA_PERSON
import org.jellyfin.androidtv.seerr.SeerrClient
import org.jellyfin.androidtv.seerr.SeerrDetails
import org.jellyfin.androidtv.seerr.SeerrPage
import org.jellyfin.androidtv.seerr.SeerrResult
import timber.log.Timber

data class SeerrRow(
	val key: String,
	@StringRes val title: Int,
	val items: List<SeerrResult>,
)

sealed interface SeerrDiscoverState {
	/** No server configured or no session for the current Jellyfin user. */
	data object NeedsSetup : SeerrDiscoverState
	data object Loading : SeerrDiscoverState
	data class Error(val message: String) : SeerrDiscoverState
	data class Content(val rows: List<SeerrRow>) : SeerrDiscoverState
}

sealed interface SeerrDetailState {
	data object Loading : SeerrDetailState
	data class Error(val message: String) : SeerrDetailState
	data class Content(
		val details: SeerrDetails,
		val isMovie: Boolean,
		val requesting: Boolean = false,
		val message: String? = null,
		val messageIsError: Boolean = false,
	) : SeerrDetailState
}

class SeerrDiscoverViewModel(
	private val seerr: SeerrClient,
) : ViewModel() {
	private val _state = MutableStateFlow<SeerrDiscoverState>(SeerrDiscoverState.Loading)
	val state: StateFlow<SeerrDiscoverState> = _state.asStateFlow()

	private val _searchResults = MutableStateFlow<List<SeerrResult>?>(null)
	/** null when no search is active. */
	val searchResults: StateFlow<List<SeerrResult>?> = _searchResults.asStateFlow()

	private val _searching = MutableStateFlow(false)
	/** True while a search is debouncing or in flight. */
	val searching: StateFlow<Boolean> = _searching.asStateFlow()

	private val _detail = MutableStateFlow<SeerrDetailState?>(null)
	val detail: StateFlow<SeerrDetailState?> = _detail.asStateFlow()

	private var searchJob: Job? = null
	private var detailJob: Job? = null

	/** Called on screen start/resume: picks up settings changes and re-validates the session. */
	fun refresh() {
		if (!seerr.hasSession) {
			_state.value = SeerrDiscoverState.NeedsSetup
			return
		}
		if (_state.value !is SeerrDiscoverState.Content) _state.value = SeerrDiscoverState.Loading
		viewModelScope.launch {
			try {
				// Always re-check: the session is per Jellyfin user and users can switch
				if (seerr.refreshUser() == null) {
					_state.value = SeerrDiscoverState.NeedsSetup
					return@launch
				}
				val sources: List<Triple<String, Int, suspend () -> SeerrPage>> = listOf(
					Triple("trending", R.string.seerr_row_trending) { seerr.trending() },
					Triple("popular_movies", R.string.seerr_row_popular_movies) { seerr.popularMovies() },
					Triple("popular_tv", R.string.seerr_row_popular_tv) { seerr.popularTv() },
					Triple("upcoming_movies", R.string.seerr_row_upcoming_movies) { seerr.upcomingMovies() },
					Triple("upcoming_tv", R.string.seerr_row_upcoming_tv) { seerr.upcomingTv() },
				)
				val rows = sources.map { (key, title, load) ->
					async {
						runCatching { load() }
							.onFailure { Timber.w(it, "Seerr row $key failed") }
							.getOrNull()
							?.let { page -> SeerrRow(key, title, page.results.filter { it.mediaType != MEDIA_PERSON }) }
					}
				}.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }

				_state.value = if (rows.isEmpty()) {
					// Every row failed: surface why (usually the server is unreachable)
					val reason = runCatching { seerr.trending() }.exceptionOrNull()?.message ?: "no content"
					if (!seerr.hasSession) SeerrDiscoverState.NeedsSetup else SeerrDiscoverState.Error(reason)
				} else SeerrDiscoverState.Content(rows)
			} catch (err: Exception) {
				Timber.w(err, "Seerr discover failed")
				_state.value = if (!seerr.hasSession) SeerrDiscoverState.NeedsSetup
				else SeerrDiscoverState.Error(err.message ?: err.toString())
			}
		}
	}

	fun search(query: String) {
		searchJob?.cancel()
		if (query.isBlank()) {
			_searching.value = false
			_searchResults.value = null
			return
		}
		_searching.value = true
		// Drop stale results so the placeholder row shows for the new query
		_searchResults.value = null
		searchJob = viewModelScope.launch {
			delay(400) // debounce typing
			_searchResults.value = runCatching { seerr.search(query.trim()) }
				.onFailure { Timber.w(it, "Seerr search failed") }
				.getOrNull()
				?.results
				?.filter { it.mediaType != MEDIA_PERSON }
				.orEmpty()
			_searching.value = false
		}
	}

	fun openDetail(item: SeerrResult) {
		detailJob?.cancel()
		_detail.value = SeerrDetailState.Loading
		detailJob = viewModelScope.launch {
			_detail.value = try {
				val details = if (item.isTv) seerr.tv(item.id) else seerr.movie(item.id)
				SeerrDetailState.Content(details, isMovie = !item.isTv)
			} catch (err: Exception) {
				Timber.w(err, "Seerr details failed")
				SeerrDetailState.Error(err.message ?: err.toString())
			}
		}
	}

	fun closeDetail() {
		detailJob?.cancel()
		_detail.value = null
	}

	/** Requests the open title. For shows, [seasons] null means every season. */
	fun request(seasons: List<Int>? = null, sentMessage: String, failedMessage: (String) -> String) {
		val current = _detail.value as? SeerrDetailState.Content ?: return
		if (current.requesting) return
		_detail.value = current.copy(requesting = true, message = null)
		viewModelScope.launch {
			try {
				val details = current.details
				if (current.isMovie) seerr.requestMovie(details.id)
				else seerr.requestTv(details.id, seasons ?: details.requestableSeasons.map { it.seasonNumber })

				// Reload so the status reflects the new request
				val refreshed = runCatching { if (current.isMovie) seerr.movie(details.id) else seerr.tv(details.id) }
					.getOrDefault(details)
				_detail.value = current.copy(details = refreshed, requesting = false, message = sentMessage, messageIsError = false)
				updateStatusInRows(refreshed, current.isMovie)
			} catch (err: Exception) {
				Timber.w(err, "Seerr request failed")
				_detail.update {
					(it as? SeerrDetailState.Content)?.copy(
						requesting = false,
						message = failedMessage(err.message ?: err.toString()),
						messageIsError = true,
					) ?: it
				}
			}
		}
	}

	private fun updateStatusInRows(details: SeerrDetails, isMovie: Boolean) {
		val info = details.mediaInfo ?: return
		// TMDB movie and tv ids are separate namespaces, so match on type too
		fun List<SeerrResult>.patch() = map { if (it.id == details.id && it.isTv != isMovie) it.copy(mediaInfo = info) else it }
		_state.update { state ->
			if (state is SeerrDiscoverState.Content) state.copy(rows = state.rows.map { it.copy(items = it.items.patch()) })
			else state
		}
		_searchResults.update { it?.patch() }
	}
}
