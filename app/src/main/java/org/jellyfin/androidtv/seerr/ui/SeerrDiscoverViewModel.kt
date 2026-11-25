package org.jellyfin.androidtv.seerr.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.seerr.MEDIA_MOVIE
import org.jellyfin.androidtv.seerr.MEDIA_PERSON
import org.jellyfin.androidtv.seerr.MEDIA_TV
import org.jellyfin.androidtv.seerr.SeerrClient
import org.jellyfin.androidtv.seerr.SeerrCredit
import org.jellyfin.androidtv.seerr.SeerrDetails
import org.jellyfin.androidtv.seerr.SeerrMediaInfo
import org.jellyfin.androidtv.seerr.SeerrMediaRequest
import org.jellyfin.androidtv.seerr.SeerrPage
import org.jellyfin.androidtv.seerr.SeerrPerson
import org.jellyfin.androidtv.seerr.SeerrPersonCredits
import org.jellyfin.androidtv.seerr.SeerrRatings
import org.jellyfin.androidtv.seerr.SeerrRequestOptions
import org.jellyfin.androidtv.seerr.SeerrResult
import org.jellyfin.androidtv.seerr.SeerrService
import org.jellyfin.androidtv.seerr.SeerrServiceProfile
import org.jellyfin.androidtv.seerr.SeerrUser
import timber.log.Timber

/** Where a "browse" grid gets its titles: a genre, a TV network or a movie studio. */
sealed interface SeerrSource {
	data class Genre(val isMovie: Boolean, val id: Int) : SeerrSource
	data class Network(val id: Int) : SeerrSource
	data class Studio(val id: Int) : SeerrSource
}

/** A Discover section: a row of posters or a row of browse tiles. */
sealed interface SeerrSection {
	val key: String
}

data class SeerrRow(
	override val key: String,
	@StringRes val title: Int,
	val items: List<SeerrResult>,
	val page: Int = 1,
	val totalPages: Int = 1,
) : SeerrSection {
	val hasMore get() = page < totalPages
}

data class SeerrTile(val name: String, val imagePath: String?, val source: SeerrSource) {
	val key get() = source.toString()
}

data class SeerrTileRow(
	override val key: String,
	@StringRes val title: Int,
	val tiles: List<SeerrTile>,
) : SeerrSection

sealed interface SeerrDiscoverState {
	/** No server configured or no session for the current Jellyfin user. */
	data object NeedsSetup : SeerrDiscoverState
	data object Loading : SeerrDiscoverState
	data class Error(val message: String) : SeerrDiscoverState
	data class Content(val sections: List<SeerrSection>) : SeerrDiscoverState {
		val rows get() = sections.filterIsInstance<SeerrRow>()
	}
}

/** A request together with the title it's for. */
data class SeerrRequestItem(
	val request: SeerrMediaRequest,
	val isMovie: Boolean,
	val tmdbId: Int,
	val title: String,
	val year: String?,
	val posterPath: String?,
) {
	fun toResult() = SeerrResult(
		id = tmdbId,
		mediaType = if (isMovie) MEDIA_MOVIE else MEDIA_TV,
		title = title,
		posterPath = posterPath,
		releaseDate = year,
		mediaInfo = request.media,
	)
}

enum class SeerrRequestsTab(@StringRes val title: Int, val filter: String, val mineOnly: Boolean) {
	MINE(R.string.seerr_requests_mine, "all", true),
	PENDING(R.string.seerr_requests_pending, "pending", false),
	ALL(R.string.seerr_requests_all, "all", false),
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
		// Loaded after the page shows, so a slow ratings lookup never holds up the title
		val ratings: SeerrRatings? = null,
		val recommendations: List<SeerrResult> = emptyList(),
		val similar: List<SeerrResult> = emptyList(),
		val user: SeerrUser? = null,
		// Radarr/Sonarr servers, for 4K and request options; empty when the user can't use either
		val services: List<SeerrService> = emptyList(),
		val profiles: Map<Int, List<SeerrServiceProfile>> = emptyMap(),
		val watchlistBusy: Boolean = false,
	) : SeerrDetailState {
		val can4k get() = user?.canRequest4k(isMovie) == true && services.any { it.is4k }
		val canChooseOptions get() = user?.canRequestAdvanced == true && services.isNotEmpty()
	}

	data class Person(
		val person: SeerrPerson,
		val knownFor: List<SeerrResult>,
		val acting: List<SeerrResult>,
		val crew: List<SeerrResult>,
	) : SeerrDetailState

	/** A grid of titles from a genre, network or studio; loads more pages as you scroll. */
	data class Browse(
		val title: String,
		val source: SeerrSource,
		val items: List<SeerrResult> = emptyList(),
		val page: Int = 0,
		val totalPages: Int = 1,
		val loading: Boolean = true,
	) : SeerrDetailState {
		val hasMore get() = page < totalPages
	}

	data class Requests(
		val tab: SeerrRequestsTab,
		val canManage: Boolean,
		val items: List<SeerrRequestItem> = emptyList(),
		val hasMore: Boolean = true,
		val loading: Boolean = true,
		val busy: Set<Int> = emptySet(),
		val message: String? = null,
	) : SeerrDetailState
}

/** One page on the title/person stack; [id] is unique per push, so the same title twice keeps two states. */
data class SeerrDetailEntry(val id: Int, val state: SeerrDetailState)

class SeerrDiscoverViewModel(
	private val seerr: SeerrClient,
) : ViewModel() {
	private val _state = MutableStateFlow<SeerrDiscoverState>(SeerrDiscoverState.Loading)
	val state: StateFlow<SeerrDiscoverState> = _state.asStateFlow()

	// Title and person pages stack up as you follow cast and recommendations; Back pops one
	private val _stack = MutableStateFlow<List<SeerrDetailEntry>>(emptyList())
	val stack: StateFlow<List<SeerrDetailEntry>> = _stack.asStateFlow()
	private var nextEntryId = 0
	private val entryJobs = mutableMapOf<Int, Job>()

	// Requests and the watchlist only carry TMDB ids; titles and posters come from these lookups
	private val detailsCache = mutableMapOf<String, SeerrDetails>()
	private val loadingMore = mutableSetOf<String>()

	private val rowLoaders: Map<String, suspend (Int) -> SeerrPage> = mapOf(
		"trending" to { page -> seerr.trending(page) },
		"popular_movies" to { page -> seerr.popularMovies(page) },
		"popular_tv" to { page -> seerr.popularTv(page) },
		"upcoming_movies" to { page -> seerr.upcomingMovies(page) },
		"upcoming_tv" to { page -> seerr.upcomingTv(page) },
	)

	private var refreshJob: Job? = null

	/**
	 * Called on screen start/resume: picks up settings changes and re-validates the session.
	 * With [discover] false (opened for a single title) the Discover rows aren't loaded at all.
	 */
	fun refresh(discover: Boolean = true) {
		if (!seerr.hasSession) {
			_state.value = SeerrDiscoverState.NeedsSetup
			return
		}
		if (!discover || refreshJob?.isActive == true) return
		val existing = _state.value as? SeerrDiscoverState.Content
		if (existing == null) _state.value = SeerrDiscoverState.Loading
		refreshJob = viewModelScope.launch {
			try {
				// Always re-check: the session is per Jellyfin user and users can switch
				val user = seerr.refreshUser()
				if (user == null) {
					_state.value = SeerrDiscoverState.NeedsSetup
					return@launch
				}

				// Coming back to Discover: keep the browsing rows (and how far they've loaded),
				// only refresh what changes behind our back
				if (existing != null) {
					refreshPersonalRows(user)
					return@launch
				}

				val sections = coroutineScope {
					val personal = async { personalRows(user) }
					val rows = rowLoaders.map { (key, load) ->
						async { runCatching { load(1) }.onFailure { Timber.w(it, "Seerr row $key failed") }.getOrNull()?.toRow(key) }
					}
					val tiles = async { tileRows() }
					val loaded = rows.awaitAll().filterNotNull().associateBy { it.key }
					val tileRows = tiles.await().associateBy { it.key }
					// Browsing rows interleaved with tiles, so Discover isn't a wall of posters
					personal.await() + listOfNotNull(
						loaded["trending"],
						loaded["popular_movies"],
						tileRows["movie_genres"],
						loaded["popular_tv"],
						tileRows["tv_genres"],
						tileRows["networks"],
						loaded["upcoming_movies"],
						tileRows["studios"],
						loaded["upcoming_tv"],
					)
				}.filter { it !is SeerrRow || it.items.isNotEmpty() }

				_state.value = if (sections.none { it is SeerrRow }) {
					// Every row failed: surface why (usually the server is unreachable)
					val reason = runCatching { seerr.trending() }.exceptionOrNull()?.message ?: "no content"
					if (!seerr.hasSession) SeerrDiscoverState.NeedsSetup else SeerrDiscoverState.Error(reason)
				} else SeerrDiscoverState.Content(sections)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr discover failed")
				_state.value = if (!seerr.hasSession) SeerrDiscoverState.NeedsSetup
				else SeerrDiscoverState.Error(err.message ?: err.toString())
			}
		}
	}

	private fun SeerrPage.toRow(key: String) = SeerrRow(
		key = key,
		title = ROW_TITLES.getValue(key),
		items = results.titlesOnly(),
		page = page,
		totalPages = totalPages,
	)

	/** "Your requests" and "Your watchlist", newest first. */
	private suspend fun personalRows(user: SeerrUser): List<SeerrRow> = coroutineScope {
		val requests = async {
			runCatching { seerr.requests(take = 20, requestedBy = user.id).results }
				.onFailure { Timber.w(it, "Seerr requests failed") }
				.getOrNull().orEmpty()
				.let { resolve(it) }
				.map { it.toResult() }
				.distinctBy { "${it.mediaType}-${it.id}" }
		}
		val watchlist = async {
			runCatching { seerr.watchlist().results }
				.onFailure { Timber.w(it, "Seerr watchlist failed") }
				.getOrNull().orEmpty()
				.take(20)
				.map { item ->
					async {
						val id = item.tmdbId ?: return@async null
						val isMovie = item.kind != MEDIA_TV
						runCatching { details(isMovie, id) }.getOrNull()?.toResult(isMovie)
					}
				}.awaitAll().filterNotNull()
		}
		listOfNotNull(
			requests.await().takeIf { it.isNotEmpty() }?.let { SeerrRow("my_requests", R.string.seerr_row_my_requests, it) },
			watchlist.await().takeIf { it.isNotEmpty() }?.let { SeerrRow("watchlist", R.string.seerr_row_watchlist, it) },
		)
	}

	private suspend fun refreshPersonalRows(user: SeerrUser) {
		val personal = personalRows(user).associateBy { it.key }
		_state.update { state ->
			if (state !is SeerrDiscoverState.Content) return@update state
			val rest = state.sections.filter { it.key !in PERSONAL_KEYS }
			state.copy(sections = PERSONAL_KEYS.mapNotNull { personal[it] } + rest)
		}
	}

	private suspend fun tileRows(): List<SeerrTileRow> = coroutineScope {
		suspend fun genres(isMovie: Boolean): List<SeerrTile> {
			// Neighbouring genres share hit films; give each tile a picture no earlier tile used
			val used = mutableSetOf<String>()
			return runCatching { seerr.genres(isMovie) }.getOrNull().orEmpty().map { genre ->
				val backdrop = genre.backdrops.firstOrNull { it !in used } ?: genre.backdrops.firstOrNull()
				backdrop?.let(used::add)
				SeerrTile(genre.name, backdrop, SeerrSource.Genre(isMovie, genre.id))
			}
		}
		val movieGenres = async { genres(true) }
		val tvGenres = async { genres(false) }
		listOfNotNull(
			movieGenres.await().takeIf { it.isNotEmpty() }?.let { SeerrTileRow("movie_genres", R.string.seerr_row_movie_genres, it) },
			tvGenres.await().takeIf { it.isNotEmpty() }?.let { SeerrTileRow("tv_genres", R.string.seerr_row_tv_genres, it) },
			SeerrTileRow("networks", R.string.seerr_row_networks, NETWORKS.map { (id, name) -> SeerrTile(name, null, SeerrSource.Network(id)) }),
			SeerrTileRow("studios", R.string.seerr_row_studios, STUDIOS.map { (id, name) -> SeerrTile(name, null, SeerrSource.Studio(id)) }),
		)
	}

	/** Loads the next page of a Discover row once focus gets near its end. */
	fun loadMore(rowKey: String) {
		val row = (_state.value as? SeerrDiscoverState.Content)?.rows?.firstOrNull { it.key == rowKey } ?: return
		val load = rowLoaders[rowKey] ?: return
		if (!row.hasMore || !loadingMore.add(rowKey)) return
		viewModelScope.launch {
			try {
				val next = load(row.page + 1)
				_state.update { state ->
					if (state !is SeerrDiscoverState.Content) return@update state
					state.copy(sections = state.sections.map { section ->
						if (section is SeerrRow && section.key == rowKey) section.copy(
							items = (section.items + next.results.titlesOnly()).distinctBy { "${it.mediaType}-${it.id}" },
							page = next.page,
							totalPages = next.totalPages,
						) else section
					})
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr row $rowKey page failed")
			} finally {
				loadingMore.remove(rowKey)
			}
		}
	}

	// --- Title pages --------------------------------------------------------------------------

	fun openDetail(item: SeerrResult) = push { id ->
		val isMovie = !item.isTv
		val details = if (isMovie) seerr.movie(item.id) else seerr.tv(item.id)
		detailsCache[cacheKey(isMovie, item.id)] = details
		// Opened straight from search or home, nothing has looked the account up yet
		val user = seerr.signedInUser.value ?: runCatching { seerr.refreshUser() }.getOrNull()
		updateEntry(id) { SeerrDetailState.Content(details, isMovie = isMovie, user = user) }

		// Extras fill in as they arrive; any of them failing just leaves that part out
		coroutineScope {
			launch {
				val ratings = runCatching { seerr.ratings(isMovie, item.id) }.getOrNull()?.takeUnless { it.isEmpty }
				if (ratings != null) updateContent(id) { it.copy(ratings = ratings) }
			}
			launch {
				val items = runCatching { seerr.recommendations(isMovie, item.id).results }.getOrNull().orEmpty().titlesOnly()
				if (items.isNotEmpty()) updateContent(id) { it.copy(recommendations = items) }
			}
			launch {
				val items = runCatching { seerr.similar(isMovie, item.id).results }.getOrNull().orEmpty().titlesOnly()
				if (items.isNotEmpty()) updateContent(id) { it.copy(similar = items) }
			}
			// Servers only matter to people who can pick 4K or request options
			if (user != null && (user.canRequest4k(isMovie) || user.canRequestAdvanced)) launch {
				val services = runCatching { seerr.services(isMovie) }.onFailure { Timber.w(it, "Seerr services failed") }.getOrNull().orEmpty()
				if (services.isNotEmpty()) updateContent(id) { it.copy(services = services) }
			}
		}
	}

	/** Quality profiles of one Radarr/Sonarr server, for the request options panel. */
	fun loadProfiles(serverId: Int) {
		val entry = _stack.value.lastOrNull() ?: return
		val content = entry.state as? SeerrDetailState.Content ?: return
		if (serverId in content.profiles) return
		viewModelScope.launch {
			val profiles = runCatching { seerr.serviceDetails(content.isMovie, serverId).profiles }
				.onFailure { Timber.w(it, "Seerr profiles failed") }
				.getOrNull().orEmpty()
			updateContent(entry.id) { it.copy(profiles = it.profiles + (serverId to profiles)) }
		}
	}

	fun openPerson(personId: Int) = push { id ->
		val (person, credits) = coroutineScope {
			val person = async { seerr.person(personId) }
			val credits = async { runCatching { seerr.personCredits(personId) }.getOrDefault(SeerrPersonCredits()) }
			person.await() to credits.await()
		}
		val cast = credits.cast.filter { it.mediaType == MEDIA_MOVIE || it.mediaType == MEDIA_TV }
		val crew = credits.crew.filter { it.mediaType == MEDIA_MOVIE || it.mediaType == MEDIA_TV }
		fun List<SeerrCredit>.results() = distinctBy { "${it.mediaType}-${it.id}" }.map { it.toResult() }
		// Crew-first people (directors, writers) are known for their crew work
		val primary = if (person.knownForDepartment == "Acting" || crew.isEmpty()) cast else crew
		updateEntry(id) {
			SeerrDetailState.Person(
				person = person,
				knownFor = primary.sortedByDescending { it.voteCount ?: 0 }.results().take(20),
				acting = cast.sortedByDescending { it.date }.results(),
				crew = crew.sortedByDescending { it.date }.results(),
			)
		}
	}

	// --- Browse grids -------------------------------------------------------------------------

	fun openBrowse(tile: SeerrTile) = push { id ->
		updateEntry(id) { SeerrDetailState.Browse(tile.name, tile.source) }
		loadBrowsePage(id)
	}

	/** Next page of the browse grid on top of the stack. */
	fun loadMoreBrowse() {
		val entry = _stack.value.lastOrNull() ?: return
		val browse = entry.state as? SeerrDetailState.Browse ?: return
		if (browse.loading || !browse.hasMore) return
		updateEntry(entry.id) { (it as? SeerrDetailState.Browse)?.copy(loading = true) ?: it }
		viewModelScope.launch { runCatching { loadBrowsePage(entry.id) }.onFailure { Timber.w(it, "Seerr browse page failed") } }
	}

	private suspend fun loadBrowsePage(id: Int) {
		val browse = _stack.value.firstOrNull { it.id == id }?.state as? SeerrDetailState.Browse ?: return
		val page = browse.page + 1
		val result = try {
			when (val source = browse.source) {
				is SeerrSource.Genre -> seerr.genre(source.isMovie, source.id, page)
				is SeerrSource.Network -> seerr.network(source.id, page)
				is SeerrSource.Studio -> seerr.studio(source.id, page)
			}
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			// The first page failing fails the page; a later one just stops loading more
			if (browse.items.isEmpty()) throw err
			updateEntry(id) { (it as? SeerrDetailState.Browse)?.copy(loading = false, totalPages = browse.page) ?: it }
			return
		}
		updateEntry(id) { state ->
			(state as? SeerrDetailState.Browse)?.copy(
				items = (state.items + result.results.titlesOnly()).distinctBy { "${it.mediaType}-${it.id}" },
				page = result.page,
				totalPages = result.totalPages,
				loading = false,
			) ?: state
		}
	}

	// --- Requests -----------------------------------------------------------------------------

	fun openRequests() = push { id ->
		val user = seerr.signedInUser.value ?: seerr.refreshUser() ?: error("Not signed in to Seerr")
		val canManage = user.canManageRequests
		// Managers land on what's waiting for them
		updateEntry(id) { SeerrDetailState.Requests(if (canManage) SeerrRequestsTab.PENDING else SeerrRequestsTab.MINE, canManage) }
		loadRequestsPage(id, reset = true)
	}

	fun selectRequestsTab(tab: SeerrRequestsTab) {
		val entry = _stack.value.lastOrNull() ?: return
		val requests = entry.state as? SeerrDetailState.Requests ?: return
		if (requests.tab == tab) return
		updateEntry(entry.id) { SeerrDetailState.Requests(tab, requests.canManage) }
		viewModelScope.launch { runCatching { loadRequestsPage(entry.id, reset = true) }.onFailure { Timber.w(it, "Seerr requests failed") } }
	}

	fun loadMoreRequests() {
		val entry = _stack.value.lastOrNull() ?: return
		val requests = entry.state as? SeerrDetailState.Requests ?: return
		if (requests.loading || !requests.hasMore) return
		updateEntry(entry.id) { (it as? SeerrDetailState.Requests)?.copy(loading = true) ?: it }
		viewModelScope.launch { runCatching { loadRequestsPage(entry.id, reset = false) }.onFailure { Timber.w(it, "Seerr requests failed") } }
	}

	private suspend fun loadRequestsPage(id: Int, reset: Boolean) {
		val requests = _stack.value.firstOrNull { it.id == id }?.state as? SeerrDetailState.Requests ?: return
		val tab = requests.tab
		val skip = if (reset) 0 else requests.items.size
		val userId = seerr.signedInUser.value?.id
		val page = try {
			seerr.requests(filter = tab.filter, take = REQUESTS_PAGE, skip = skip, requestedBy = if (tab.mineOnly) userId else null)
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			updateEntry(id) { (it as? SeerrDetailState.Requests)?.copy(loading = false, hasMore = false, message = err.message) ?: it }
			return
		}
		val items = resolve(page.results)
		updateEntry(id) { state ->
			// The tab may have changed while this page loaded
			if (state !is SeerrDetailState.Requests || state.tab != tab) return@updateEntry state
			state.copy(
				items = (if (reset) items else state.items + items).distinctBy { it.request.id },
				hasMore = page.pageInfo.page < page.pageInfo.pages,
				loading = false,
			)
		}
	}

	/** Approves or declines a request (request managers only). */
	fun setRequestApproved(requestId: Int, approve: Boolean, failedMessage: (String) -> String) {
		val entry = _stack.value.lastOrNull() ?: return
		if (entry.state !is SeerrDetailState.Requests) return
		updateEntry(entry.id) { (it as? SeerrDetailState.Requests)?.copy(busy = it.busy + requestId, message = null) ?: it }
		viewModelScope.launch {
			try {
				val updated = if (approve) seerr.approveRequest(requestId) else seerr.declineRequest(requestId)
				updateEntry(entry.id) { state ->
					if (state !is SeerrDetailState.Requests) return@updateEntry state
					val items = state.items.mapNotNull { item ->
						when {
							item.request.id != requestId -> item
							// Handled requests leave the "waiting for approval" list
							state.tab == SeerrRequestsTab.PENDING -> null
							else -> item.copy(request = item.request.copy(status = updated.status ?: item.request.status))
						}
					}
					state.copy(items = items, busy = state.busy - requestId)
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr approve/decline failed")
				updateEntry(entry.id) {
					(it as? SeerrDetailState.Requests)?.copy(busy = it.busy - requestId, message = failedMessage(err.message ?: err.toString())) ?: it
				}
			}
		}
	}

	/** Pairs requests with their titles and posters. */
	private suspend fun resolve(requests: List<SeerrMediaRequest>): List<SeerrRequestItem> = coroutineScope {
		requests.map { request ->
			async {
				val media = request.media ?: return@async null
				val tmdbId = media.tmdbId ?: return@async null
				val isMovie = (request.type ?: media.mediaType) != MEDIA_TV
				val details = runCatching { details(isMovie, tmdbId) }.getOrNull()
				SeerrRequestItem(
					request = request,
					isMovie = isMovie,
					tmdbId = tmdbId,
					title = details?.displayTitle ?: "#$tmdbId",
					year = details?.year,
					posterPath = details?.posterPath,
				)
			}
		}.awaitAll().filterNotNull()
	}

	private fun cacheKey(isMovie: Boolean, id: Int) = "${if (isMovie) MEDIA_MOVIE else MEDIA_TV}-$id"

	private suspend fun details(isMovie: Boolean, id: Int): SeerrDetails =
		detailsCache[cacheKey(isMovie, id)] ?: (if (isMovie) seerr.movie(id) else seerr.tv(id)).also {
			detailsCache[cacheKey(isMovie, id)] = it
		}

	private fun SeerrDetails.toResult(isMovie: Boolean) = SeerrResult(
		id = id,
		mediaType = if (isMovie) MEDIA_MOVIE else MEDIA_TV,
		title = title,
		name = name,
		posterPath = posterPath,
		releaseDate = releaseDate,
		firstAirDate = firstAirDate,
		mediaInfo = mediaInfo,
	)

	// --- Stack plumbing -----------------------------------------------------------------------

	/** Pops the top page. Returns false when that was the last one. */
	fun closeDetail(): Boolean {
		val top = _stack.value.lastOrNull() ?: return false
		entryJobs.remove(top.id)?.cancel()
		_stack.update { it.dropLast(1) }
		return _stack.value.isNotEmpty()
	}

	private fun push(load: suspend CoroutineScope.(id: Int) -> Unit) {
		// A second press while a page loads would stack a duplicate that Back then has to close
		if (_stack.value.lastOrNull()?.state == SeerrDetailState.Loading) return
		val id = nextEntryId++
		_stack.update { it + SeerrDetailEntry(id, SeerrDetailState.Loading) }
		entryJobs[id] = viewModelScope.launch {
			try {
				load(id)
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr page failed")
				updateEntry(id) { SeerrDetailState.Error(err.message ?: err.toString()) }
			}
		}
	}

	private fun updateEntry(id: Int, transform: (SeerrDetailState) -> SeerrDetailState) =
		_stack.update { stack -> stack.map { if (it.id == id) it.copy(state = transform(it.state)) else it } }

	private fun updateContent(id: Int, transform: (SeerrDetailState.Content) -> SeerrDetailState) =
		updateEntry(id) { if (it is SeerrDetailState.Content) transform(it) else it }

	private fun List<SeerrResult>.titlesOnly() = filter { it.mediaType != MEDIA_PERSON }

	// --- Actions on the open title ------------------------------------------------------------

	/** Requests the title on top of the stack. For shows, [seasons] null means every missing season. */
	fun request(
		seasons: List<Int>? = null,
		options: SeerrRequestOptions = SeerrRequestOptions(),
		sentMessage: String,
		failedMessage: (String) -> String,
	) {
		val entry = _stack.value.lastOrNull() ?: return
		val current = entry.state as? SeerrDetailState.Content ?: return
		if (current.requesting) return
		updateContent(entry.id) { it.copy(requesting = true, message = null) }
		viewModelScope.launch {
			try {
				val details = current.details
				if (current.isMovie) seerr.requestMovie(details.id, options)
				else seerr.requestTv(details.id, seasons ?: details.missingSeasons(options.is4k).map { it.seasonNumber }, options)

				// Reload so the status reflects the new request
				val refreshed = runCatching { if (current.isMovie) seerr.movie(details.id) else seerr.tv(details.id) }
					.getOrDefault(details)
				detailsCache[cacheKey(current.isMovie, details.id)] = refreshed
				updateContent(entry.id) { it.copy(details = refreshed, requesting = false, message = sentMessage, messageIsError = false) }
				updateStatusInRows(refreshed, current.isMovie)
				seerr.signedInUser.value?.let { refreshPersonalRows(it) }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr request failed")
				updateContent(entry.id) {
					it.copy(requesting = false, message = failedMessage(err.message ?: err.toString()), messageIsError = true)
				}
			}
		}
	}

	fun toggleWatchlist(failedMessage: (String) -> String) {
		val entry = _stack.value.lastOrNull() ?: return
		val current = entry.state as? SeerrDetailState.Content ?: return
		if (current.watchlistBusy) return
		val details = current.details
		val add = details.onUserWatchlist != true
		updateContent(entry.id) { it.copy(watchlistBusy = true, message = null) }
		viewModelScope.launch {
			try {
				if (add) seerr.addToWatchlist(details.id, current.isMovie, details.displayTitle)
				else seerr.removeFromWatchlist(details.id, current.isMovie)
				val updated = details.copy(onUserWatchlist = add)
				detailsCache[cacheKey(current.isMovie, details.id)] = updated
				updateContent(entry.id) { it.copy(details = updated, watchlistBusy = false) }
				seerr.signedInUser.value?.let { refreshPersonalRows(it) }
			} catch (err: CancellationException) {
				throw err
			} catch (err: Exception) {
				Timber.w(err, "Seerr watchlist failed")
				updateContent(entry.id) {
					it.copy(watchlistBusy = false, message = failedMessage(err.message ?: err.toString()), messageIsError = true)
				}
			}
		}
	}

	private fun updateStatusInRows(details: SeerrDetails, isMovie: Boolean) {
		val info: SeerrMediaInfo = details.mediaInfo ?: return
		// TMDB movie and tv ids are separate namespaces, so match on type too
		fun List<SeerrResult>.patch() = map { if (it.id == details.id && it.isTv != isMovie) it.copy(mediaInfo = info) else it }
		_state.update { state ->
			if (state is SeerrDiscoverState.Content) state.copy(sections = state.sections.map { if (it is SeerrRow) it.copy(items = it.items.patch()) else it })
			else state
		}
		// Pages further down the stack show the same title in their rows
		_stack.update { stack ->
			stack.map { entry ->
				entry.copy(
					state = when (val state = entry.state) {
						is SeerrDetailState.Content -> state.copy(recommendations = state.recommendations.patch(), similar = state.similar.patch())
						is SeerrDetailState.Person -> state.copy(knownFor = state.knownFor.patch(), acting = state.acting.patch(), crew = state.crew.patch())
						is SeerrDetailState.Browse -> state.copy(items = state.items.patch())
						else -> state
					}
				)
			}
		}
	}

	private companion object {
		const val REQUESTS_PAGE = 20
		val PERSONAL_KEYS = listOf("my_requests", "watchlist")

		val ROW_TITLES = mapOf(
			"trending" to R.string.seerr_row_trending,
			"popular_movies" to R.string.seerr_row_popular_movies,
			"popular_tv" to R.string.seerr_row_popular_tv,
			"upcoming_movies" to R.string.seerr_row_upcoming_movies,
			"upcoming_tv" to R.string.seerr_row_upcoming_tv,
		)

		// TMDB ids, the same set Seerr's web app offers
		val NETWORKS = listOf(
			213 to "Netflix", 2739 to "Disney+", 1024 to "Prime Video", 2552 to "Apple TV+", 453 to "Hulu",
			49 to "HBO", 3186 to "Max", 4330 to "Paramount+", 3353 to "Peacock", 67 to "Showtime",
			318 to "Starz", 174 to "AMC", 4 to "BBC One", 2 to "ABC", 6 to "NBC", 16 to "CBS",
			19 to "FOX", 71 to "The CW", 56 to "Cartoon Network", 80 to "Adult Swim",
		)
		val STUDIOS = listOf(
			2 to "Disney", 3 to "Pixar", 420 to "Marvel Studios", 9993 to "DC", 1 to "Lucasfilm",
			174 to "Warner Bros.", 33 to "Universal", 4 to "Paramount", 34 to "Sony Pictures",
			127928 to "20th Century", 521 to "DreamWorks", 41077 to "A24", 10342 to "Studio Ghibli",
			3172 to "Blumhouse", 1632 to "Lionsgate",
		)
	}
}
