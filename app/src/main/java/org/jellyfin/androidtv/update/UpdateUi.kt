package org.jellyfin.androidtv.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.koin.compose.koinInject

/** Checks for updates at startup and offers newer releases. Hosted in MainActivity. */
@Composable
fun ErgUpdatePrompt() {
	val updater = koinInject<ErgUpdater>()
	val state by updater.state.collectAsState()
	val scope = rememberCoroutineScope()

	LaunchedEffect(Unit) { updater.check() }

	val available = state as? ErgUpdater.State.Available
	DialogBase(
		visible = available != null,
		onDismissRequest = { updater.dismiss() },
	) {
		val release = available?.release ?: return@DialogBase
		val focus = remember { FocusRequester() }
		LaunchedEffect(release.tagName) { runCatching { focus.requestFocus() } }

		Column(
			modifier = Modifier
				.width(520.dp)
				.background(JellyfinTheme.colorScheme.surface, RoundedCornerShape(16.dp))
				.padding(28.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(stringResource(R.string.erg_update_available, release.version), fontSize = 22.sp, fontWeight = FontWeight.Bold)
			Text(stringResource(R.string.erg_update_current, updater.currentVersion), color = JellyfinTheme.colorScheme.listCaption)
			release.body?.takeIf { it.isNotBlank() }?.let {
				Text(it.take(600), maxLines = 8, color = JellyfinTheme.colorScheme.listCaption)
			}
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
				Button(onClick = { scope.launch { updater.install(release) } }, modifier = Modifier.focusRequester(focus)) {
					Text(stringResource(R.string.erg_update_install))
				}
				Button(onClick = { updater.dismiss() }) { Text(stringResource(R.string.erg_update_later)) }
			}
		}
	}
}
