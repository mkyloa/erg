package org.jellyfin.androidtv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.seerr.SeerrClient
import org.jellyfin.androidtv.seerr.SeerrResult
import timber.log.Timber
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SearchViewModel(
	private val searchRepository: SearchRepository,
	private val seerr: SeerrClient,
) : ViewModel() {
	companion object {
		private val debounceDuration = 600.milliseconds

		private val groups = mapOf(
			R.string.lbl_movies to setOf(BaseItemKind.MOVIE),
			R.string.lbl_series to setOf(BaseItemKind.SERIES),
			R.string.lbl_episodes to setOf(BaseItemKind.EPISODE),
			R.string.lbl_videos to setOf(BaseItemKind.VIDEO),
			R.string.lbl_programs to setOf(BaseItemKind.LIVE_TV_PROGRAM),
			R.string.channels to setOf(BaseItemKind.LIVE_TV_CHANNEL),
			R.string.lbl_playlists to setOf(BaseItemKind.PLAYLIST),
			R.string.lbl_artists to setOf(BaseItemKind.MUSIC_ARTIST),
			R.string.lbl_albums to setOf(BaseItemKind.MUSIC_ALBUM),
			R.string.lbl_songs to setOf(BaseItemKind.AUDIO),
			R.string.photo_albums to setOf(BaseItemKind.PHOTO_ALBUM),
			R.string.photos to setOf(BaseItemKind.PHOTO),
			R.string.lbl_collections to setOf(BaseItemKind.BOX_SET),
			R.string.lbl_people to setOf(BaseItemKind.PERSON),
		)
	}

	private var searchJob: Job? = null
	private var seerrJob: Job? = null

	// Erg: titles from Seerr that are not in the library, so they can be requested from search
	private val _seerrResults = MutableStateFlow<List<SeerrResult>>(emptyList())
	val seerrResults = _seerrResults.asStateFlow()

	// Erg: Seerr's trending titles, shown before anything is typed
	private val _trending = MutableStateFlow<List<SeerrResult>>(emptyList())
	val trending = _trending.asStateFlow()

	val seerrEnabled get() = seerr.hasSession

	init {
		if (seerr.hasSession) viewModelScope.launch {
			_trending.value = runCatching { seerr.trending().results.requestable(includeAvailable = true) }
				.onFailure { Timber.w(it, "Seerr trending failed") }
				.getOrDefault(emptyList())
		}
	}

	private var previousQuery: String? = null

	private val _searchResultsFlow = MutableStateFlow<Collection<SearchResultGroup>>(emptyList())
	val searchResultsFlow = _searchResultsFlow.asStateFlow()

	private val _isSearching = MutableStateFlow(false)
	/** True from the moment a query is accepted (including the debounce) until its results arrive. */
	val isSearching = _isSearching.asStateFlow()

	fun searchImmediately(query: String) = searchDebounced(query, 0.milliseconds)

	fun searchDebounced(query: String, debounce: Duration = debounceDuration): Boolean {
		val trimmed = query.trim()
		if (trimmed == previousQuery) return false
		previousQuery = trimmed

		searchJob?.cancel()
		seerrJob?.cancel()
		_seerrResults.value = emptyList()

		if (trimmed.isBlank()) {
			_isSearching.value = false
			_searchResultsFlow.value = emptyList()
			return true
		}

		// Separate from the library search, so a slow Seerr never holds up library results
		if (seerr.hasSession) seerrJob = viewModelScope.launch {
			delay(debounce)
			_seerrResults.value = runCatching { seerr.search(trimmed).results.requestable(includeAvailable = false) }
				.onFailure { Timber.w(it, "Seerr search failed") }
				.getOrDefault(emptyList())
		}

		_isSearching.value = true
		searchJob = viewModelScope.launch {
			delay(debounce)

			_searchResultsFlow.value = groups.map { (stringRes, itemKinds) ->
				async {
					val result = searchRepository.search(trimmed, itemKinds)
					val items = result.getOrNull().orEmpty()

					SearchResultGroup(stringRes, items)
				}
			}.awaitAll().filter { it.items.isNotEmpty() }
			_isSearching.value = false
		}

		return true
	}
}

/** Movies and shows only; [includeAvailable] false drops what is already in the library. */
private fun List<SeerrResult>.requestable(includeAvailable: Boolean) = this
	.filter { it.isMovie || it.isTv }
	.filter { includeAvailable || !it.status.isAvailable }
	.take(SEERR_ROW_LIMIT)

private const val SEERR_ROW_LIMIT = 20
