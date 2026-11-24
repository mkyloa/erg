package org.jellyfin.androidtv.seerr.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.data.service.BackgroundService
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
		/** Opens Discover with this search already run (from the library search's "Search Seerr"). */
		const val EXTRA_QUERY = "query"
	}

	private val viewModel: SeerrDiscoverViewModel by viewModel()
	private val settingsViewModel: SettingsViewModel by activityViewModel()
	private val navigationRepository: NavigationRepository by inject()
	private val backgroundService: BackgroundService by inject()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		JellyfinTheme {
			SeerrDiscoverScreen(
				viewModel = viewModel,
				initialQuery = arguments?.getString(EXTRA_QUERY),
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
