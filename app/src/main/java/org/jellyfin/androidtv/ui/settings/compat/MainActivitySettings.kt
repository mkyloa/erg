package org.jellyfin.androidtv.ui.settings.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun MainActivitySettings() {
	val viewModel = koinActivityViewModel<SettingsViewModel>()
	val visible by viewModel.visible.collectAsState()
	val startRoute by viewModel.startRoute.collectAsState()

	// Re-create the router when asked to open on a different screen
	key(startRoute) {
		ProvideRouter(routes, startRoute) {
			SettingsDialog(
				visible = visible,
				onDismissRequest = { viewModel.hide() }
			) {
				SettingsRouterContent()
			}
		}
	}
}
