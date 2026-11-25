package org.jellyfin.androidtv.ui.home

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.GenreRowType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import org.jellyfin.androidtv.seerr.SeerrClient
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.LocalDateTime

/**
 * DUNE's curated home rows ("genre rows"): up to ten slots, each set to a [GenreRowType]
 * in Settings → Home, shown below the regular home sections.
 */
class HomeCuratedRows(
	private val context: Context,
	private val userRepository: UserRepository,
	private val userSettingPreferences: UserSettingPreferences,
	private val api: ApiClient,
) {
	/** Configured row types in slot order, without NONE or duplicates. */
	val enabledTypes: List<GenreRowType>
		get() = userSettingPreferences.genrerows
			.map { userSettingPreferences[it] }
			.filter { it != GenreRowType.NONE }
			.distinct()

	/** Builds the rows; runs network lookups (e.g. for "Because you watched") off the main thread. */
	suspend fun build(): List<HomeFragmentRow> = withContext(Dispatchers.IO) {
		val userId = userRepository.currentUser.value?.id ?: return@withContext emptyList()
		// In parallel, and Seerr rows capped: home waits for all of these before showing any row,
		// so a Seerr server that is off must not hold it up for the full connect timeout
		enabledTypes.map { type ->
			async {
				runCatching {
					if (type == GenreRowType.SEERR_AVAILABLE || type == GenreRowType.SEERR_TRENDING) {
						withTimeoutOrNull(SEERR_ROW_TIMEOUT_MS) { row(type, userId) }
					} else row(type, userId)
				}
					.onFailure { Timber.w(it, "Failed to build curated row $type") }
					.getOrNull()
			}
		}.awaitAll().filterNotNull()
	}

	private suspend fun row(type: GenreRowType, userId: java.util.UUID): HomeFragmentRow? = when (type) {
		GenreRowType.SUGGESTED_MOVIES -> {
			// "Because you watched X": items similar to the most recently played movie
			val lastPlayed = api.itemsApi.getItems(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.MOVIE),
				filters = setOf(ItemFilter.IS_PLAYED),
				sortBy = setOf(ItemSortBy.DATE_PLAYED),
				sortOrder = setOf(SortOrder.DESCENDING),
				recursive = true,
				limit = 1,
			).content.items.firstOrNull()

			lastPlayed?.let { movie ->
				HomeFragmentBrowseRowDefRow(
					BrowseRowDef(
						context.getString(R.string.because_you_watched, movie.name.orEmpty()),
						GetSimilarItemsRequest(
							itemId = movie.id,
							userId = userId,
							fields = ItemRepository.itemFields,
							limit = ITEM_LIMIT,
						),
						QueryType.SimilarMovies,
					)
				)
			}
		}

		GenreRowType.COLLECTIONS -> items(
			R.string.show_collections_row,
			GetItemsRequest(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.BOX_SET),
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
			),
		)

		GenreRowType.DISCOVER_MOVIES -> items(
			R.string.show_discover_movies_row,
			GetItemsRequest(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.MOVIE),
				filters = setOf(ItemFilter.IS_UNPLAYED),
				sortBy = setOf(ItemSortBy.RANDOM),
			),
		)

		GenreRowType.DISCOVER_SERIES -> items(
			R.string.show_discover_series_row,
			GetItemsRequest(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.SERIES),
				filters = setOf(ItemFilter.IS_UNPLAYED),
				sortBy = setOf(ItemSortBy.RANDOM),
			),
		)

		GenreRowType.RECENTLY_RELEASED -> items(
			R.string.show_recently_released_row,
			GetItemsRequest(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
				maxPremiereDate = LocalDateTime.now(),
				sortBy = setOf(ItemSortBy.PREMIERE_DATE),
				sortOrder = setOf(SortOrder.DESCENDING),
			),
		)

		GenreRowType.WATCH_IT_AGAIN -> items(
			R.string.show_watch_it_again_row,
			GetItemsRequest(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
				filters = setOf(ItemFilter.IS_PLAYED),
				sortBy = setOf(ItemSortBy.DATE_PLAYED),
				sortOrder = setOf(SortOrder.DESCENDING),
			),
		)

		GenreRowType.MUSIC -> items(
			R.string.show_music_videos_row,
			GetItemsRequest(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.MUSIC_VIDEO),
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
			),
		)

		GenreRowType.SEERR_AVAILABLE -> seerrAvailableRow(userId)
		GenreRowType.SEERR_TRENDING -> seerrTrendingRow()

		GenreRowType.NONE -> null
	}

	/** Your Seerr requests that have reached the library, as regular library cards. */
	private suspend fun seerrAvailableRow(userId: java.util.UUID): HomeFragmentRow? {
		val seerr = org.koin.java.KoinJavaComponent.get<SeerrClient>(SeerrClient::class.java)
		if (!seerr.hasSession) return null
		val me = seerr.signedInUser.value ?: seerr.refreshUser() ?: return null
		val ids = seerr.requests(filter = "available", take = ITEM_LIMIT, requestedBy = me.id).results
			.mapNotNull { it.media?.let { media -> if (it.is4k) media.jellyfinMediaId4k else media.jellyfinMediaId } }
			.mapNotNull { it.toUUIDOrNull() }
			.distinct()
		if (ids.isEmpty()) return null
		return items(
			R.string.seerr_home_row_available,
			GetItemsRequest(
				userId = userId,
				ids = ids,
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
			),
		)
	}

	private suspend fun seerrTrendingRow(): HomeFragmentRow? {
		val seerr = org.koin.java.KoinJavaComponent.get<SeerrClient>(SeerrClient::class.java)
		if (!seerr.hasSession) return null
		// Only what isn't in the library yet; the rest is already on the home screen
		val items = seerr.trending().results
			.filter { (it.isMovie || it.isTv) && !it.status.isAvailable }
			.take(ITEM_LIMIT)
		if (items.isEmpty()) return null
		return SeerrHomeRow(context.getString(R.string.seerr_home_row_trending), items)
	}

	private fun items(title: Int, query: GetItemsRequest) = HomeFragmentBrowseRowDefRow(
		BrowseRowDef(
			context.getString(title),
			query.copy(
				recursive = true,
				limit = ITEM_LIMIT,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				fields = ItemRepository.itemFields,
			),
			ITEM_LIMIT,
			false,
			true,
			arrayOf(ChangeTriggerType.LibraryUpdated),
		)
	)

	private companion object {
		const val SEERR_ROW_TIMEOUT_MS = 4_000L
		const val ITEM_LIMIT = 20
	}
}
