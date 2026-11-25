package org.jellyfin.androidtv.ui.home

import androidx.compose.animation.togetherWith
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.doOnLayout
import androidx.core.view.doOnDetach
import androidx.core.view.doOnAttach
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.databinding.FragmentHomeBinding
import org.jellyfin.androidtv.databinding.FragmentHomeClassicBinding
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.AsyncImageView
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.koin.compose.viewmodel.koinActivityViewModel
import org.jellyfin.androidtv.ui.home.carousel.CarouselUiState
import org.jellyfin.androidtv.ui.home.carousel.CarouselViewModel
import org.jellyfin.androidtv.ui.home.carousel.FeaturedCarousel
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.UUID

class HomeFragment : Fragment() {
    private val api: ApiClient by inject()
    private val imageHelper: ImageHelper by inject()
    private val carouselViewModel: CarouselViewModel by viewModel()
    private var _modernBinding: FragmentHomeBinding? = null
    private var _classicBinding: FragmentHomeClassicBinding? = null
    private val modernBinding get() = _modernBinding!!
    private val classicBinding get() = _classicBinding!!
    private var useClassicLayout = false
    private val currentBinding get() = if (useClassicLayout) _classicBinding else _modernBinding
    private val scrollListeners = mutableListOf<RecyclerView.OnScrollListener>()
    private var isScrolling = false
    private val scrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var scrollCheckRunnable: Runnable? = null
    private val scrollCheckDelay = 350L
    private var interactionDelayRunnable: Runnable? = null
    private var _isCarouselPaused = mutableStateOf(false)
    val isCarouselPaused: Boolean get() = _isCarouselPaused.value

    private fun findRecyclerViews(viewGroup: ViewGroup): List<RecyclerView> {
        val recyclerViews = mutableListOf<RecyclerView>()

        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            when (child) {
                is RecyclerView -> recyclerViews.add(child)
                is ViewGroup -> recyclerViews.addAll(findRecyclerViews(child))
            }
        }

        return recyclerViews
    }



    private fun findAsyncImageViews(viewGroup: ViewGroup): List<AsyncImageView> {
        val asyncImageViews = mutableListOf<AsyncImageView>()

        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            when (child) {
                is AsyncImageView -> asyncImageViews.add(child)
                is ViewGroup -> asyncImageViews.addAll(findAsyncImageViews(child))
            }
        }

        return asyncImageViews
    }

    private fun cleanupScrollListeners() {
        val currentBinding = currentBinding ?: return

        scrollListeners.forEach { listener ->
            findRecyclerViews(currentBinding.root as ViewGroup).forEach { recyclerView ->
                recyclerView.removeOnScrollListener(listener)
            }
        }
        scrollListeners.clear()

        scrollCheckRunnable?.let {
            scrollHandler.removeCallbacks(it)
            scrollCheckRunnable = null
        }
    }

    @JvmField
    var isReadyForInteraction = false

    private val sessionRepository by inject<SessionRepository>()
    private val navigationRepository by inject<NavigationRepository>()
    private val mediaManager by inject<MediaManager>()
    private val playbackLauncher: PlaybackLauncher by inject()
    private val userSettingPreferences: UserSettingPreferences by inject()
    private val userPreferences: UserPreferences by inject()
    private val backgroundService by inject<org.jellyfin.androidtv.data.service.BackgroundService>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        useClassicLayout = userSettingPreferences.get(userSettingPreferences.useClassicHomeScreen)

        return if (useClassicLayout) {
            _classicBinding = FragmentHomeClassicBinding.inflate(inflater, container, false)
            classicBinding.root
        } else {
            _modernBinding = FragmentHomeBinding.inflate(inflater, container, false)
            modernBinding.root
        }
    }

    override fun onResume() {
        super.onResume()
        isReadyForInteraction = true

        _isCarouselPaused.value = false

        refreshBackgroundState()
    }

    override fun onPause() {
        super.onPause()

        _isCarouselPaused.value = true

        try {
            backgroundService.unblockAllBackgrounds()
        } catch (e: Exception) {
        }
    }

    override fun onDestroyView() {
        try {
            view?.let { v ->
                if (v.isAttachedToWindow) {
                    v.viewTreeObserver.removeOnWindowFocusChangeListener { /* no-op */ }
                    v.removeCallbacks(null)
                    v.setOnClickListener(null)
                    v.setOnKeyListener(null)
                }
            }

            if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
                try {
                    viewLifecycleOwner.lifecycleScope.coroutineContext[Job]?.cancel()
                } catch (e: Exception) {
                }
            }

            cleanupScrollListeners()

            _modernBinding = null
            _classicBinding = null
        } catch (e: Exception) {
        } finally {
            super.onDestroyView()
        }
    }

    override fun onDestroy() {
        try {
            clearReferences()

            if (!isRemoving && !isStateSaved) {
                try {
                    parentFragmentManager
                        .beginTransaction()
                        .remove(this)
                        .commitAllowingStateLoss()
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        } finally {
            super.onDestroy()
        }
    }

    private fun clearReferences() {
        try {
            scrollCheckRunnable?.let {
                scrollHandler.removeCallbacks(it)
                scrollCheckRunnable = null
            }

            interactionDelayRunnable?.let {
                view?.removeCallbacks(it)
                interactionDelayRunnable = null
            }

            view?.let { v ->
                if (v.isAttachedToWindow) {
                    v.viewTreeObserver.removeOnWindowFocusChangeListener { /* no-op */ }
                }
            }

            cleanupScrollListeners()

            view?.let { v -> 
                v.setOnClickListener(null)
                v.setOnKeyListener(null)
                v.removeCallbacks(null)
            }

            if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
                try {
                    viewLifecycleOwner.lifecycleScope.coroutineContext[Job]?.cancel()
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!useClassicLayout) setupHeroCollapse()

        view.isFocusableInTouchMode = false
        view.isClickable = false

        interactionDelayRunnable = Runnable {
            isReadyForInteraction = true
            view.isFocusableInTouchMode = true
            view.isClickable = true

        }
        view.postDelayed(interactionDelayRunnable!!, 2000)

        val toolbarBinding = if (useClassicLayout) classicBinding.toolbar else modernBinding.toolbar
        toolbarBinding.setContent {
            val searchAction = {
                navigationRepository.navigate(Destinations.search())
            }
            val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
            val settingsAction = { settingsViewModel.show() }
            val switchUsersAction = {
                switchUser()
            }

            val liveTvAction = {
    val lastChannelId = org.jellyfin.androidtv.ui.livetv.TvManager.getLastLiveTvChannel()
    if (lastChannelId != null) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val channel = withContext(Dispatchers.IO) {
                    api.liveTvApi.getChannel(lastChannelId).content
                }
                playbackLauncher.launch(requireContext(), listOf(channel), 0, false, 0, false)
            } catch (e: Exception) {
                navigationRepository.navigate(Destinations.liveTvGuide)
            }
        }
    } else {
        navigationRepository.navigate(Destinations.liveTvGuide)
    }
}
            val libraryAction = {
                navigationRepository.navigate(Destinations.home)
            }

            val favoritesAction = {
                navigationRepository.navigate(Destinations.favorites)
            }

            val openRandomMovie: (BaseItemDto) -> Unit = { item ->
                item.id?.let { idStr ->
                    try {
                        val uuid = UUID.fromString(idStr.toString())
                        navigationRepository.navigate(Destinations.itemDetails(uuid))
                    } catch (e: Exception) {
                    }
                }
            }

            org.jellyfin.androidtv.ui.shared.toolbar.HomeToolbar(
                openSearch = { searchAction() },
                openLiveTv = { liveTvAction() },
                openSettings = { settingsAction() },
                switchUsers = { switchUsersAction() },
                openLibrary = { libraryAction() },
                onFavoritesClick = { favoritesAction() },
                openDiscover = { navigationRepository.navigate(Destinations.seerrDiscover) },
                openRandomMovie = openRandomMovie,
                userSettingPreferences = userSettingPreferences
            )
        }

        if (!useClassicLayout) {
            modernBinding.carouselContainer?.setContent {
            val carouselUiState by carouselViewModel.uiState.collectAsState()

            // Fade from the skeleton into the carousel
            androidx.compose.animation.AnimatedContent(
                targetState = carouselUiState,
                // Only animate when the kind of state changes, not on every update
                contentKey = { it::class },
                transitionSpec = {
                	androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(350)) togetherWith
                		androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(350))
                },
                label = "carousel-state",
            ) { state ->
            when (state) {
                is CarouselUiState.Loading -> {
                    // Carousel-shaped placeholder instead of a spinner
                    org.jellyfin.androidtv.ui.composable.SkeletonHero(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
                is CarouselUiState.Success -> {
                    val activeIndex by carouselViewModel.activeIndex.collectAsState()
                    FeaturedCarousel(
                        items = state.items,
                        onItemSelected = { item ->
                            try {
                                val uuid = java.util.UUID.fromString(item.id)
                                navigationRepository.navigate(Destinations.itemDetails(uuid))
                            } catch (e: Exception) {
                            }
                        },
                        isPaused = isCarouselPaused,
                        activeIndex = activeIndex,
                        onActiveIndexChanged = { newIndex ->
                            carouselViewModel.setActiveIndex(newIndex)
                        }
                    )

                }
                is CarouselUiState.Empty -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusable()
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        Key.DirectionUp -> {
                                            toolbarBinding.requestFocus()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    Color.Transparent.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            androidx.compose.material3.Text(
                                text = "No featured content available",
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = org.jellyfin.androidtv.ui.base.JellyfinTheme.colorScheme.text,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                is CarouselUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            androidx.compose.material3.Text(
                                text = "Error loading featured content",
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error
                            )

                            androidx.compose.material3.TextButton(
                                onClick = { carouselViewModel.refresh() },
                                modifier = Modifier.padding(top = 16.dp)
                            ) {
                                androidx.compose.material3.Text("Retry")
                            }
                        }
                    }
                }
            }
            }
            }
        }
    }

    private fun refreshBackgroundState() {
        try {
            if (useClassicLayout) {
                backgroundService.unblockAllBackgrounds()
            } else {
                backgroundService.blockAllBackgrounds()
            }
        } catch (e: Exception) {
        }
    }

    private fun switchUser() {
        if (!isReadyForInteraction) return

        mediaManager.clearAudioQueue()
        sessionRepository.destroyCurrentSession()

        val selectUserIntent = Intent(activity, StartupActivity::class.java)
        selectUserIntent.putExtra(StartupActivity.EXTRA_HIDE_SPLASH, true)
        selectUserIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)

        activity?.startActivity(selectUserIntent)
        activity?.finishAfterTransition()
    }

    private fun openItemDetails(item: org.jellyfin.sdk.model.api.BaseItemDto) {
        item.id?.let { idStr ->
            val uuid = try {
                UUID.fromString(idStr.toString())
            } catch (e: Exception) {
                null
            }
            if (uuid != null) {
                navigationRepository.navigate(Destinations.itemDetails(uuid))

            }
        }
    }

    // Erg: the hero takes half the screen, leaving room for one row. Once focus moves past the
    // first row it slides away and the rows take the space; back on the first row it returns.
    private var heroCollapsed = false
    private var expandedRowsHeight = 0

    private fun setupHeroCollapse() {
        // The fragment outlives its views: coming back from a detail page builds a new, expanded
        // layout, so the collapse state starts over with it.
        heroCollapsed = false
        expandedRowsHeight = 0
        val root = modernBinding.root
        val rows = modernBinding.contentView
        val hero = modernBinding.carouselContainer ?: return
        val listener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null || _modernBinding == null) return@OnGlobalFocusChangeListener
            val inRows = generateSequence(newFocus as View?) { it.parent as? View }.any { it === rows }
            val inHero = !inRows && generateSequence(newFocus as View?) { it.parent as? View }.any { it === hero }
            val position = (childFragmentManager.findFragmentById(rows.id) as? HomeRowsFragment)?.selectedPosition ?: 0
            when {
                inRows && position > 0 -> setHeroCollapsed(true)
                inRows || inHero -> setHeroCollapsed(false)
            }
        }
        // Register on the window's observer once attached and remove it from that same observer on
        // detach; removing via the detached view's observer missed it, so every visit to a detail
        // page left a listener (and the old views it held) behind.
        root.doOnAttach { attached ->
            val observer = attached.viewTreeObserver
            observer.addOnGlobalFocusChangeListener(listener)
            attached.doOnDetach { if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(listener) }
        }
    }

    private fun setHeroCollapsed(collapsed: Boolean) {
        if (collapsed == heroCollapsed) return
        heroCollapsed = collapsed
        val root = _modernBinding?.root ?: return
        // Restoring focus after coming back can collapse before the first layout, when the screen
        // height is still 0 and the rows would get no height at all. Apply it once laid out.
        if (root.height == 0) root.doOnLayout { if (heroCollapsed == collapsed) applyHeroCollapsed(collapsed, animate = false) }
        else applyHeroCollapsed(collapsed, animate = true)
    }

    private fun applyHeroCollapsed(collapsed: Boolean, animate: Boolean) {
        val binding = _modernBinding ?: return
        val hero = binding.carouselContainer ?: return
        val rows = binding.contentView
        val root = binding.root as ViewGroup
        if (expandedRowsHeight == 0) expandedRowsHeight = rows.layoutParams.height

        if (animate) android.transition.TransitionManager.beginDelayedTransition(
            root,
            android.transition.TransitionSet()
                .addTransition(android.transition.ChangeBounds())
                .addTransition(android.transition.Fade())
                .setDuration(220)
                .addListener(object : android.transition.TransitionListenerAdapter() {
                    override fun onTransitionEnd(transition: android.transition.Transition) = alignSelectedRow()
                }),
        )
        hero.visibility = if (collapsed) View.GONE else View.VISIBLE
        rows.layoutParams = rows.layoutParams.apply {
            // Everything under the toolbar
            height = if (collapsed) root.height - (64 * resources.displayMetrics.density).toInt() else expandedRowsHeight
        }
        _isCarouselPaused.value = collapsed
        if (!animate) rows.post { alignSelectedRow() }
    }

    // The resize animation suppresses the rows' layout, which cancels leanback's scroll to the newly
    // selected row; without this, moving up to the first row left it scrolled out of view.
    private fun alignSelectedRow() {
        val rows = _modernBinding?.contentView ?: return
        val grid = (childFragmentManager.findFragmentById(rows.id) as? HomeRowsFragment)?.verticalGridView ?: return
        val row = grid.layoutManager?.findViewByPosition(grid.selectedPosition) ?: return
        val offset = row.top - (HomeRowsFragment.ROW_ALIGNMENT_DP * resources.displayMetrics.density).toInt()
        if (offset != 0) grid.smoothScrollBy(0, offset)
    }
}
