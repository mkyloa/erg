package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.koin.android.ext.android.inject
import org.jellyfin.playback.core.PlaybackManager
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userPreferences by inject<UserPreferences>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository, userPreferences) }

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true
	// Erg: the pinned rows on screen and the setting they were built from, so pin changes show up on return
	private var builtPins: String? = null
	private var pinnedHomeRows: List<Row> = emptyList()
	private var firstCuratedRow: Row? = null
	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }
	private val curatedRows by lazy { HomeCuratedRows(requireContext(), userRepository, userSettingPreferences, api) }
	private val liveTVRow by lazy { HomeFragmentLiveTVRow(requireActivity(), userRepository) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Create a custom row presenter that keeps headers always visible
		val rowPresenter = PositionableListRowPresenter(requireContext(), false, true)

		// Set the adapter with our custom row presenter
		adapter = MutableObjectAdapter<Row>(rowPresenter) as ObjectAdapter

		lifecycleScope.launch(Dispatchers.IO) {
			val startTime = System.currentTimeMillis()
			val currentUser = withTimeout(30.seconds) {
				userRepository.currentUser.filterNotNull().first()
			}

			// Start out with default sections
			val homesections = userSettingPreferences.activeHomesections
			val includeLiveTvRows = currentUser.policy?.enableLiveTvAccess == true

			// Make sure the rows are empty
			val rows = mutableListOf<HomeFragmentRow>()

			// Check for coroutine cancellation
			if (!isActive) return@launch

			Timber.d("Starting parallel loading of ${homesections.size} home sections")
			val userViewsDeferred = async(Dispatchers.IO) {
				userViewsRepository.views.first()
			}

			// Load all home sections in parallel for better performance
			val sectionRows = homesections.map { section ->
				async(Dispatchers.IO) {
					when (section) {
						HomeSectionType.LATEST_MEDIA -> {
							val views = userViewsDeferred.await()
							helper.loadRecentlyAdded(views)
						}
						HomeSectionType.LIBRARY_TILES_SMALL -> HomeFragmentViewsRow(small = false)
						HomeSectionType.LIBRARY_BUTTONS -> HomeFragmentViewsRow(small = true)
						HomeSectionType.RESUME -> helper.loadResumeVideoLandscape()
						HomeSectionType.RESUME_AUDIO -> helper.loadResumeAudio()
						HomeSectionType.RESUME_BOOK -> null // Books are not (yet) supported
						HomeSectionType.ACTIVE_RECORDINGS -> helper.loadLatestLiveTvRecordings()
						HomeSectionType.NEXT_UP -> helper.loadNextUpLandscape()
						HomeSectionType.CONTINUE_WATCHING_COMBINED -> helper.loadContinueWatchingCombined()
						HomeSectionType.LIVE_TV -> if (includeLiveTvRows) {
							listOf(liveTVRow, helper.loadOnNow())
						} else {
							emptyList<HomeFragmentRow>()
						}
						HomeSectionType.NONE -> null
					}
				}
			}.awaitAll()

			sectionRows.forEach { sectionResult ->
				when (sectionResult) {
					is List<*> -> rows.addAll(sectionResult.filterNotNull().map { it as HomeFragmentRow })
					else -> sectionResult?.let { rows.add(it as HomeFragmentRow) }
				}
			}

			val sectionLoadTime = System.currentTimeMillis() - startTime
			Timber.d("Loaded ${rows.size} home sections in parallel in ${sectionLoadTime}ms")

			// Erg: rows the user pinned (collections, playlists, genres, studios)
			val pins = userSettingPreferences[userSettingPreferences.pinnedRows]
			val pinned = PinnedRows.rows(requireContext(), userSettingPreferences, userRepository)

			// Erg curated rows ("genre rows"), shown below the regular sections
			val curated = curatedRows.build()

			// Add sections to layout
			withContext(Dispatchers.Main) {
				val cardPresenter = CardPresenter(false, 170)

				@Suppress("UNCHECKED_CAST")
				val rowsAdapter = adapter as MutableObjectAdapter<Row>
				val layoutStartTime = System.currentTimeMillis()

				notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
				nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)

				rows.forEach { row ->
					try {
						if (row is HomeFragmentViewsRow && row.shouldUseCenteredLayout()) {
							row.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
						} else {
							row.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
						}
					} catch (e: Exception) {
						Timber.e(e, "Error adding row to adapter")
					}
				}
				val beforePinned = rowsAdapter.size()
				pinned.forEach { row ->
					runCatching { row.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter) }
						.onFailure { Timber.e(it, "Error adding pinned row") }
				}
				pinnedHomeRows = (beforePinned until rowsAdapter.size()).mapNotNull { rowsAdapter.get(it) }
				builtPins = pins
				val beforeCurated = rowsAdapter.size()
				curated.forEach { row ->
					runCatching { row.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter) }
						.onFailure { Timber.e(it, "Error adding curated row") }
				}

				firstCuratedRow = rowsAdapter.get(beforeCurated)

				// Wire up Live TV sibling rows so the On Now row removes the buttons row when empty
				for (i in 0 until rowsAdapter.size()) {
					val listRow = rowsAdapter.get(i) as? ListRow ?: continue
					val itemAdapter = listRow.adapter as? ItemRowAdapter ?: continue
					if (itemAdapter.queryType == QueryType.LiveTvProgram && i > 0) {
						val previousRow = rowsAdapter.get(i - 1)
						if (previousRow != null) itemAdapter.setSiblingRow(previousRow)
					}
				}

				val layoutTime = System.currentTimeMillis() - layoutStartTime
				Timber.d("Home sections layout completed in ${layoutTime}ms")
			}

			val totalLoadTime = System.currentTimeMillis() - startTime
			Timber.d("HomeRowsFragment initialization completed in ${totalLoadTime}ms")
		}

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				api.webSocket.subscribe<UserDataChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)

				api.webSocket.subscribe<LibraryChangedMessage>()
				.onEach {
					refreshRows(force = true, delayed = false)
				}
				.launchIn(this)
			}
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	/** Swaps the pinned rows for a fresh set, in the same place, after pins were added or removed. */
	private fun reloadPinnedRows(pins: String) {
		builtPins = pins
		@Suppress("UNCHECKED_CAST")
		val rowsAdapter = adapter as MutableObjectAdapter<Row>
		val anchor = pinnedHomeRows.firstNotNullOfOrNull { row -> rowsAdapter.indexOf(row).takeIf { it >= 0 } }
		pinnedHomeRows.forEach { rowsAdapter.remove(it) }
		val insertAt = anchor
			?: firstCuratedRow?.let { row -> rowsAdapter.indexOf(row).takeIf { it >= 0 } }
			?: rowsAdapter.size()

		// Rows add themselves at the end (their adapters keep a reference to rowsAdapter), then move into place
		val before = rowsAdapter.size()
		val cardPresenter = CardPresenter(false, 170)
		PinnedRows.rows(requireContext(), userSettingPreferences, userRepository).forEach { row ->
			runCatching { row.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter) }
				.onFailure { Timber.e(it, "Error adding pinned row") }
		}
		val added = (before until rowsAdapter.size()).mapNotNull { rowsAdapter.get(it) }
		added.forEach { rowsAdapter.remove(it) }
		added.forEachIndexed { i, row -> rowsAdapter.add((insertAt + i).coerceAtMost(rowsAdapter.size()), row) }
		pinnedHomeRows = added
	}

	override fun onResume() {
		super.onResume()

		//React to deletion
		if (currentRow != null && currentItem != null && currentItem?.baseItem != null && currentItem!!.baseItem!!.id == dataRefreshService.lastDeletedItemId) {
			(currentRow!!.adapter as ItemRowAdapter).remove(currentItem)
			currentItem = null
			dataRefreshService.lastDeletedItemId = null
		}

		if (!justLoaded) {
			val pins = userSettingPreferences[userSettingPreferences.pinnedRows]
			if (builtPins != null && pins != builtPins) reloadPinnedRows(pins)
			refreshCurrentItem()
			refreshRows()
		} else {
			justLoaded = false
		}
		// Ensure views are updated when fragment is resumed
		ensureViewsInitialized()
		// Update audio queue
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		@Suppress("UNCHECKED_CAST")
		(adapter as MutableObjectAdapter<Row>).let { mutableAdapter ->
    nowPlaying.update(requireContext(), mutableAdapter)
}
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		@Suppress("UNCHECKED_CAST")
		(adapter as MutableObjectAdapter<Row>).let { mutableAdapter ->
    nowPlaying.update(requireContext(), mutableAdapter)
}
	}

	private fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
		lifecycleScope.launch(Dispatchers.Main) {
			if (delayed) delay(1.5.seconds)

			try {
				val size = adapter.size()
				repeat(size) { i ->
					val row = adapter[i] as? ListRow ?: return@repeat
					val rowAdapter = row.adapter as? ItemRowAdapter ?: return@repeat

					// small delay between row refreshes to prevent UI freezing
					if (i > 0) delay(90)

					try {
						if (force) {
							rowAdapter.Retrieve()
						} else {
							rowAdapter.ReRetrieveIfNeeded()
						}
					} catch (e: Exception) {
						Timber.e(e, "Error refreshing row at position $i")
					}
				}
			} catch (e: Exception) {
				Timber.e(e, "Error during refreshRows")
			}
		}
	}

	private fun refreshCurrentItem() {
		val adapter = currentRow?.adapter as? ItemRowAdapter ?: return
		val item = currentItem ?: return

		Timber.i("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroyView() {
        // Clear references to views to prevent leaks
        titleView = null
        infoRowView = null
        summaryView = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaManager.removeAudioEventListener(this)
    }

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item is GridButton) {
				when (item.id) {
					LiveTvOption.LIVE_TV_GUIDE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvGuide)
					LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSchedule)
					LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvRecordings)
					LiveTvOption.LIVE_TV_SERIES_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSeriesRecordings)
				}
			}

			if (item !is BaseRowItem) return
			itemLauncher.launch(item, (row as ListRow).adapter as ItemRowAdapter, requireContext())
		}
	}

	private var titleView: android.widget.TextView? = null
    private var infoRowView: android.widget.LinearLayout? = null
    private var summaryView: android.widget.TextView? = null
    private var logoView: org.jellyfin.androidtv.ui.AsyncImageView? = null

    private fun ensureViewsInitialized() {
        // Always reinitialize the views when this is called
        activity?.let { activity ->
            titleView = activity.findViewById<android.widget.TextView>(R.id.title)
            infoRowView = activity.findViewById<android.widget.LinearLayout>(R.id.infoRow)
            summaryView = activity.findViewById<android.widget.TextView>(R.id.summary)
            logoView = activity.findViewById<org.jellyfin.androidtv.ui.AsyncImageView>(R.id.logo)

            // If we have a current item, update the views with its data
            currentItem?.let { item ->
                updateLogoAndTitle(item)
                summaryView?.setText(item.getSummary(requireContext()) ?: "")
                infoRowView?.removeAllViews()
                infoRowView?.let { view ->
                    org.jellyfin.androidtv.util.InfoLayoutHelper.addInfoRow(requireContext(), item.baseItem, view, true)
                }
            } ?: run {
                // Clear the views if there's no current item
                clearInfoPanel()
            }
        }
    }

    private fun updateLogoAndTitle(item: BaseRowItem) {
        val baseItem = item.baseItem
        val api: ApiClient by inject()
        val imageHelper: org.jellyfin.androidtv.util.ImageHelper by inject()
        val itemLogo = baseItem?.itemImages?.get(org.jellyfin.sdk.model.api.ImageType.LOGO)
        val parentLogo = baseItem?.parentImages?.get(org.jellyfin.sdk.model.api.ImageType.LOGO)
        val logoUrl = itemLogo?.getUrl(api) ?: parentLogo?.getUrl(api)

        if (logoUrl != null) {
            // Show logo, hide title
            logoView?.visibility = android.view.View.VISIBLE
            titleView?.visibility = android.view.View.GONE

            logoView?.load(
                url = logoUrl,
                aspectRatio = 0.1
            )
        } else {
            // Hide logo, show title
            logoView?.visibility = android.view.View.GONE
            titleView?.visibility = android.view.View.VISIBLE
            titleView?.setText(item.getName(requireContext()))
        }
    }


    private fun clearInfoPanel(forceClear: Boolean = false) {
        titleView?.setText("")
        logoView?.visibility = android.view.View.GONE
        titleView?.visibility = android.view.View.VISIBLE
        infoRowView?.removeAllViews()
        summaryView?.setText("")

        // Only clear backgrounds if we're not on a Media Folders item or if forced
        if (forceClear || currentItem == null || !homeFragmentViewsRow.isMediaFoldersItem(currentItem)) {
            backgroundService.clearBackgrounds()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureViewsInitialized()

        // Initialize any additional views here
        initializeViews()
    }

    private fun initializeViews() {
        // Keep the focused row (title included) at the top of the rows area. Leanback's default
        // offset left tall rows with their title cut off and the previous row peeking above.
        setAlignment((ROW_ALIGNMENT_DP * resources.displayMetrics.density).toInt())
    }

private val homeFragmentViewsRow = HomeFragmentViewsRow(small = false)

private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
    override fun onItemSelected(
        itemViewHolder: Presenter.ViewHolder?,
        item: Any?,
        rowViewHolder: RowPresenter.ViewHolder?,
        row: Row?,
    ) {
        ensureViewsInitialized()

        // Check if the item is from the Media Folders row or has the media_folders_item tag
        val isMediaFolderItem = homeFragmentViewsRow.isMediaFoldersItem(item) ||
                              (item is BaseRowItem && itemViewHolder?.view?.tag == "media_folders_item")

        if (isMediaFolderItem) {
            // For Media Folders items, clear the info panel but keep the background
            titleView?.setText("")
            logoView?.visibility = android.view.View.GONE
            titleView?.visibility = android.view.View.VISIBLE
            infoRowView?.removeAllViews()
            summaryView?.setText("")

            // Set the background using the Media Folder's primary image
            if (item is BaseRowItem) {
                currentItem = item
                currentRow = row as? ListRow
                backgroundService.setBackground(item.baseItem)
            }
            return
        }

        if (item !is BaseRowItem) {
            currentItem = null
            // Clear info panel and background
            clearInfoPanel(true)
        } else {
            currentItem = item
            currentRow = row as? ListRow

            // Safely cast row to ListRow and get its adapter
            (row as? ListRow)?.let { listRow ->
                val itemRowAdapter = listRow.adapter as? ItemRowAdapter
                itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))
            }

            updateLogoAndTitle(item)
            summaryView?.setText(item.getSummary(requireContext()) ?: "")
            infoRowView?.removeAllViews()
            infoRowView?.let { view ->
                org.jellyfin.androidtv.util.InfoLayoutHelper.addInfoRow(requireContext(), item.baseItem, view, true)
            }

            backgroundService.setBackground(item.baseItem)
        }
    }
}

	companion object {
		// Distance from the top of the rows area to the selected row
		const val ROW_ALIGNMENT_DP = 24
	}
}
