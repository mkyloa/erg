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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

	LaunchedEffect(Unit) { updater.check() }

	// Once the user picks "Update now" here, keep showing progress and any failure, not just the offer
	var started by remember { mutableStateOf(false) }
	val release = when (val s = state) {
		is ErgUpdater.State.Available -> s.release
		is ErgUpdater.State.Downloading -> s.release.takeIf { started }
		is ErgUpdater.State.NeedsPermission -> s.release.takeIf { started }
		is ErgUpdater.State.Installing -> s.release.takeIf { started }
		else -> null
	}
	val error = (state as? ErgUpdater.State.Error)?.message?.takeIf { started }
	if (state is ErgUpdater.State.Idle || state is ErgUpdater.State.UpToDate) started = false

	DialogBase(
		visible = release != null || error != null,
		onDismissRequest = { started = false; if (state is ErgUpdater.State.Available || error != null) updater.dismiss() },
	) {
		val focus = remember { FocusRequester() }
		LaunchedEffect(state::class) { runCatching { focus.requestFocus() } }

		Column(
			modifier = Modifier
				.width(520.dp)
				.background(JellyfinTheme.colorScheme.surface, RoundedCornerShape(16.dp))
				.padding(28.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			if (error != null) {
				Text(stringResource(R.string.erg_update_failed, error), fontSize = 18.sp)
				Button(onClick = { started = false; updater.dismiss() }, modifier = Modifier.padding(top = 8.dp).focusRequester(focus)) {
					Text(stringResource(R.string.erg_update_close))
				}
				return@Column
			}
			release ?: return@Column

			Text(stringResource(R.string.erg_update_available, release.version), fontSize = 22.sp, fontWeight = FontWeight.Bold)
			when (val s = state) {
				is ErgUpdater.State.Available -> {
					Text(stringResource(R.string.erg_update_current, updater.currentVersion), color = JellyfinTheme.colorScheme.listCaption)
					ErgUpdater.readableNotes(release.body)?.let {
						Text(it.take(700), maxLines = 10, color = JellyfinTheme.colorScheme.listCaption)
					}
					Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
						Button(onClick = { started = true; updater.install(release) }, modifier = Modifier.focusRequester(focus)) {
							Text(stringResource(R.string.erg_update_install))
						}
						Button(onClick = { updater.dismiss() }) { Text(stringResource(R.string.erg_update_later)) }
						Button(onClick = { updater.skip() }) { Text(stringResource(R.string.erg_update_skip)) }
					}
				}
				else -> {
					val caption = when (s) {
						is ErgUpdater.State.Downloading -> stringResource(R.string.erg_update_downloading, (s.progress * 100).toInt())
						is ErgUpdater.State.NeedsPermission -> stringResource(R.string.erg_update_needs_permission)
						else -> stringResource(R.string.erg_update_installing)
					}
					Text(caption, color = JellyfinTheme.colorScheme.listCaption)
					Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
						if (s is ErgUpdater.State.NeedsPermission) {
							Button(onClick = { updater.install(release) }, modifier = Modifier.focusRequester(focus)) {
								Text(stringResource(R.string.erg_update_install))
							}
						}
						// Hides the dialog; the download carries on and Settings → Updates shows it
						Button(
							onClick = { started = false },
							modifier = if (s is ErgUpdater.State.NeedsPermission) Modifier else Modifier.focusRequester(focus),
						) { Text(stringResource(R.string.erg_update_hide)) }
					}
				}
			}
		}
	}
}
