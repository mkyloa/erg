package org.jellyfin.androidtv.seerr.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.seerr.SeerrResult
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

/** Browse and request titles from Seerr. */
class SeerrDiscoverFragment : Fragment() {
	companion object {
		/** Opens straight onto this title's page (from search); Back leaves Discover. */
		const val EXTRA_ITEM_ID = "item_id"
		const val EXTRA_ITEM_TYPE = "item_type"
	}

	private val viewModel: SeerrDiscoverViewModel by viewModel()
	private val settingsViewModel: SettingsViewModel by activityViewModel()
	private val navigationRepository: NavigationRepository by inject()
	private val backgroundService: BackgroundService by inject()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		JellyfinTheme {
			SeerrDiscoverScreen(
				viewModel = viewModel,
				initialItem = arguments?.takeIf { it.containsKey(EXTRA_ITEM_ID) }?.let {
					SeerrResult(id = it.getInt(EXTRA_ITEM_ID), mediaType = it.getString(EXTRA_ITEM_TYPE))
				},
				onCloseInitialItem = { navigationRepository.goBack() },
				onOpenSearch = { navigationRepository.navigate(Destinations.search()) },
				onOpenSettings = { settingsViewModel.show(Routes.SEERR) },
				onOpenLibraryItem = { id ->
					val uuid = id.toUUIDOrNull()
					if (uuid != null) navigationRepository.navigate(Destinations.itemDetails(uuid))
					else Timber.w("Seerr returned an invalid Jellyfin id: $id")
				},
			)
		}
	}

	override fun onResume() {
		super.onResume()
		// The screen draws its own backdrops
		backgroundService.clearBackgrounds()
		// Picks up a sign-in made in the settings dialog
		viewModel.refresh()
	}
}
