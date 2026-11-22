package org.jellyfin.androidtv.ui.settings.compat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.androidtv.ui.settings.Routes

class SettingsViewModel : ViewModel() {
	private val _visible = MutableStateFlow(false)
	val visible get() = _visible.asStateFlow()

	private val _startRoute = MutableStateFlow(Routes.MAIN)
	val startRoute get() = _startRoute.asStateFlow()

	/** Opens the settings dialog, optionally directly on a sub-screen (e.g. [Routes.SEERR]). */
	fun show(route: String = Routes.MAIN) {
		_startRoute.value = route
		_visible.value = true
	}

	fun hide() {
		_visible.value = false
	}
}
