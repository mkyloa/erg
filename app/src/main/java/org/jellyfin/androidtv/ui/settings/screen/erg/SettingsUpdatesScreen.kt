package org.jellyfin.androidtv.ui.settings.screen.erg

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.update.ErgUpdater
import org.koin.compose.koinInject

@Composable
fun SettingsUpdatesScreen() {
	val updater = koinInject<ErgUpdater>()
	val state by updater.state.collectAsState()
	val scope = rememberCoroutineScope()
	var autoCheck by remember { mutableStateOf(updater.autoCheck) }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.erg_updates)) },
				captionContent = { Text(stringResource(R.string.erg_update_current, updater.currentVersion)) },
			)
		}

		if (!updater.isSupported) {
			item { ListSection(headingContent = { Text(stringResource(R.string.erg_update_unsupported)) }) }
			return@SettingsColumn
		}

		item {
			val caption = when (val s = state) {
				ErgUpdater.State.Checking -> stringResource(R.string.erg_update_checking)
				ErgUpdater.State.UpToDate -> stringResource(R.string.erg_update_up_to_date)
				is ErgUpdater.State.Available -> stringResource(R.string.erg_update_available, s.release.version)
				is ErgUpdater.State.Downloading -> stringResource(R.string.erg_update_downloading, (s.progress * 100).toInt())
				is ErgUpdater.State.NeedsPermission -> stringResource(R.string.erg_update_needs_permission)
				is ErgUpdater.State.Installing -> stringResource(R.string.erg_update_installing)
				is ErgUpdater.State.Error -> stringResource(R.string.erg_update_failed, s.message)
				ErgUpdater.State.Idle -> null
			}
			val release = (state as? ErgUpdater.State.Available)?.release ?: (state as? ErgUpdater.State.NeedsPermission)?.release
			ListButton(
				headingContent = { Text(stringResource(if (release != null) R.string.erg_update_install else R.string.erg_update_check)) },
				captionContent = caption?.let { { Text(it) } },
				enabled = state !is ErgUpdater.State.Checking && state !is ErgUpdater.State.Downloading && state !is ErgUpdater.State.Installing,
				onClick = {
					if (release != null) updater.install(release) else scope.launch { updater.check(force = true) }
				},
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_update_auto)) },
				trailingContent = { Checkbox(checked = autoCheck) },
				onClick = {
					autoCheck = !autoCheck
					updater.autoCheck = autoCheck
				},
			)
		}
	}
}
