package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.koin.java.KoinJavaComponent
import java.time.LocalDateTime

class HomeFragmentHelper(
	private val context: Context,
	private val userRepository: UserRepository,
	private val userPreferences: UserPreferences,
) {
	fun loadRecentlyAdded(userViews: Collection<BaseItemDto>): HomeFragmentRow {
		return HomeFragmentLatestRow(userRepository, userViews)
	}

	fun loadResume(title: String, includeMediaTypes: Collection<MediaType>): HomeFragmentRow {
		val query = GetResumeItemsRequest(
			limit = ITEM_LIMIT_RESUME,
			fields = ItemRepository.browseFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			mediaTypes = includeMediaTypes,
			excludeItemTypes = setOf(BaseItemKind.AUDIO_BOOK),
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(title, query, 0, false, true, arrayOf(ChangeTriggerType.TvPlayback, ChangeTriggerType.MoviePlayback)))
	}

	fun loadResumeVideo(): HomeFragmentRow {
		return loadResume(context.getString(R.string.lbl_continue_watching), listOf(MediaType.VIDEO))
	}

	fun loadResumeAudio(): HomeFragmentRow {
		return loadResume(context.getString(R.string.continue_listening), listOf(MediaType.AUDIO))
	}

	fun loadLatestLiveTvRecordings(): HomeFragmentRow {
		val query = GetRecordingsRequest(
			fields = ItemRepository.itemFields,
			enableImages = true,
			limit = ITEM_LIMIT_RECORDINGS
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_recordings), query))
	}

	fun loadNextUp(): HomeFragmentRow {
		val maxDays = userPreferences[UserPreferences.homeNextUpMaxDays]
		val nextUpDateCutoff = maxDays.takeIf { it > 0 }?.let { LocalDateTime.now().minusDays(it.toLong()) }

		val query = GetNextUpRequest(
			imageTypeLimit = 1,
			limit = ITEM_LIMIT_NEXT_UP,
			enableResumable = false,
			fields = ItemRepository.browseFields,
			nextUpDateCutoff = nextUpDateCutoff,
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_next_up), query, arrayOf(ChangeTriggerType.TvPlayback)))
	}

	fun loadOnNow(): HomeFragmentRow {
		val query = GetRecommendedProgramsRequest(
			isAiring = true,
			fields = ItemRepository.itemFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = ITEM_LIMIT_ON_NOW
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_on_now), query))
	}


	// DUNE: landscape, label-free cards for resume / next up rows
	private fun landscapePresenter() = CardPresenter(false, ImageType.THUMB, 140, true)

	private fun HomeFragmentRow.withPresenter(presenter: CardPresenter) = object : HomeFragmentRow {
		override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) =
			this@withPresenter.addToRowsAdapter(context, presenter, rowsAdapter)
	}

	fun loadResumeVideoLandscape(): HomeFragmentRow = loadResumeVideo().withPresenter(landscapePresenter())
	fun loadNextUpLandscape(): HomeFragmentRow = loadNextUp().withPresenter(landscapePresenter())

	fun loadMusicRow(): HomeFragmentRow {
		val musicPlaylistQuery = GetItemsRequest(
			userId = userRepository.currentUser.value?.id,
			includeItemTypes = setOf(BaseItemKind.PLAYLIST),
			mediaTypes = setOf(MediaType.AUDIO),
			sortBy = setOf(ItemSortBy.SORT_NAME),
			limit = ITEM_LIMIT_MUSIC,
			fields = ItemRepository.itemFields,
			recursive = true,
			excludeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
		)
		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_music_playlists), musicPlaylistQuery, 50))
	}

	fun loadContinueWatchingCombined(): HomeFragmentRow = object : HomeFragmentRow {
		override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
			val apiClient = KoinJavaComponent.get<ApiClient>(ApiClient::class.java)
			val combinedAdapter = CombinedResumeNextUpAdapter(
				userRepository = userRepository,
				userPreferences = userPreferences,
				cardPresenter = landscapePresenter(),
				rowsAdapter = rowsAdapter,
				apiClient = apiClient,
			)
			val row = ListRow(HeaderItem(context.getString(R.string.home_section_continue_nextup)), combinedAdapter)
			combinedAdapter.setRow(row)
			rowsAdapter.add(row)
			combinedAdapter.loadData()
		}
	}

	companion object {
		// Maximum amount of items loaded for a row
		private const val ITEM_LIMIT_RESUME = 50
		private const val ITEM_LIMIT_RECORDINGS = 40
		private const val ITEM_LIMIT_NEXT_UP = 50
		private const val ITEM_LIMIT_ON_NOW = 20
		private const val ITEM_LIMIT_MUSIC = 40
	}
}
