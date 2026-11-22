package org.jellyfin.androidtv.ui.settings.screen.seerr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.seerr.SeerrClient
import org.jellyfin.androidtv.seerr.SeerrException
import org.jellyfin.androidtv.seerr.ui.SeerrTextField
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject
import timber.log.Timber

private sealed interface QuickConnectState {
	data object Idle : QuickConnectState
	data class Waiting(val code: String) : QuickConnectState
	data class Failed(val message: String) : QuickConnectState
}

@Composable
fun SettingsSeerrScreen() {
	val seerr = koinInject<SeerrClient>()
	val jellyfinAddress = koinInject<org.jellyfin.sdk.api.client.ApiClient>().baseUrl
	val scope = rememberCoroutineScope()
	val user by seerr.signedInUser.collectAsState()

	var url by remember { mutableStateOf(seerr.serverUrl.orEmpty()) }
	var urlError by remember { mutableStateOf<String?>(null) }
	var quickConnect by remember { mutableStateOf<QuickConnectState>(QuickConnectState.Idle) }
	var quickConnectAttempt by remember { mutableStateOf(0) }
	var showPasswordForm by remember { mutableStateOf(false) }
	var useLocalAccount by remember { mutableStateOf(false) }
	var username by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	var busy by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }

	val invalidUrlMessage = stringResource(R.string.seerr_server_url_invalid)
	val expiredMessage = stringResource(R.string.seerr_quick_connect_expired)

	fun saveUrl(): Boolean {
		if (url.isBlank()) {
			seerr.serverUrl = null
			urlError = null
			return false
		}
		val normalized = SeerrClient.normalizeUrl(url)
		if (normalized == null) {
			urlError = invalidUrlMessage
			return false
		}
		urlError = null
		url = normalized
		seerr.serverUrl = normalized
		return true
	}

	LaunchedEffect(Unit) {
		runCatching { seerr.refreshUser() }.onFailure { Timber.w(it, "Seerr session check failed") }
	}

	// Quick Connect: start, then poll until approved or expired
	LaunchedEffect(quickConnectAttempt) {
		if (quickConnectAttempt == 0) return@LaunchedEffect
		error = null
		try {
			val init = seerr.quickConnectInitiate()
			quickConnect = QuickConnectState.Waiting(init.code)
			repeat(150) { // ~5 minutes
				delay(2_000)
				val approved = try {
					seerr.quickConnectCheck(init.secret)
				} catch (err: SeerrException) {
					if (err.code == 404) {
						quickConnect = QuickConnectState.Failed(expiredMessage)
						return@LaunchedEffect
					}
					throw err
				}
				if (approved) {
					seerr.quickConnectAuthenticate(init.secret)
					quickConnect = QuickConnectState.Idle
					return@LaunchedEffect
				}
			}
			quickConnect = QuickConnectState.Failed(expiredMessage)
		} catch (err: Exception) {
			Timber.w(err, "Seerr Quick Connect failed")
			quickConnect = QuickConnectState.Failed(err.message ?: err.toString())
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.app_name).uppercase()) },
				headingContent = { Text(stringResource(R.string.seerr)) },
				captionContent = { Text(stringResource(R.string.seerr_description)) },
			)
		}

		item {
			Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
				Text(stringResource(R.string.seerr_server_url), color = JellyfinTheme.colorScheme.listOverline, fontSize = 12.sp)
				SeerrTextField(
					value = url,
					onValueChange = { url = it; urlError = null },
					placeholder = stringResource(R.string.seerr_server_url_hint),
					keyboardType = KeyboardType.Uri,
					onSubmit = { saveUrl() },
					modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
				)
				urlError?.let { Text(it, color = JellyfinTheme.colorScheme.recording, fontSize = 12.sp) }
			}
		}

		item {
			ListSection(
				headingContent = {
					Text(
						user?.let { stringResource(R.string.seerr_signed_in_as, it.name) }
							?: stringResource(R.string.seerr_not_signed_in)
					)
				},
			)
		}

		if (user == null) {
			item {
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_users), contentDescription = null) },
					headingContent = { Text(stringResource(R.string.seerr_sign_in_quick_connect)) },
					captionContent = { Text(stringResource(R.string.seerr_sign_in_quick_connect_caption)) },
					enabled = !busy,
					onClick = {
						if (saveUrl()) quickConnectAttempt++
					},
				)
			}

			when (val state = quickConnect) {
				is QuickConnectState.Waiting -> {
					item {
						ListSection(
							headingContent = { Text(stringResource(R.string.seerr_quick_connect_code, state.code), fontSize = 28.sp) },
							captionContent = { Text(stringResource(R.string.seerr_quick_connect_waiting)) },
						)
					}
					// Scan to open the Jellyfin server's Quick Connect page with the code filled in
					jellyfinAddress?.let { address ->
						item {
							val qr = androidx.compose.runtime.remember(state.code) {
								runCatching { org.jellyfin.androidtv.util.QrCode.bitmap(org.jellyfin.androidtv.util.QrCode.quickConnectUrl(address, state.code), 480) }.getOrNull()
							}
							if (qr != null) androidx.compose.foundation.Image(
								bitmap = qr.asImageBitmap(),
								contentDescription = stringResource(R.string.erg_qr_sign_in),
								modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).size(160.dp),
							)
						}
					}
				}

				is QuickConnectState.Failed -> item {
					ListSection(headingContent = { Text(state.message, color = JellyfinTheme.colorScheme.recording) })
				}

				QuickConnectState.Idle -> Unit
			}

			item {
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
					headingContent = { Text(stringResource(R.string.seerr_sign_in_password)) },
					onClick = { showPasswordForm = !showPasswordForm },
				)
			}

			if (showPasswordForm) {
				item {
					ListButton(
						headingContent = { Text(stringResource(R.string.seerr_sign_in_local)) },
						trailingContent = { Checkbox(checked = useLocalAccount) },
						onClick = { useLocalAccount = !useLocalAccount },
					)
				}

				item {
					Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
						SeerrTextField(
							value = username,
							onValueChange = { username = it },
							placeholder = stringResource(if (useLocalAccount) R.string.seerr_email else R.string.seerr_username),
							keyboardType = if (useLocalAccount) KeyboardType.Email else KeyboardType.Text,
							imeAction = ImeAction.Next,
							modifier = Modifier.fillMaxWidth(),
						)
						SeerrTextField(
							value = password,
							onValueChange = { password = it },
							placeholder = stringResource(R.string.seerr_password),
							password = true,
							modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
						)
					}
				}

				item {
					ListButton(
						headingContent = { Text(stringResource(R.string.seerr_sign_in)) },
						enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
						onClick = {
							if (!saveUrl()) return@ListButton
							busy = true
							error = null
							scope.launch {
								try {
									if (useLocalAccount) seerr.signInLocal(username.trim(), password)
									else seerr.signInJellyfin(username.trim(), password)
									password = ""
									showPasswordForm = false
								} catch (err: Exception) {
									Timber.w(err, "Seerr sign in failed")
									error = err.message ?: err.toString()
								} finally {
									busy = false
								}
							}
						},
					)
				}
			}
		} else {
			item {
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_logout), contentDescription = null) },
					headingContent = { Text(stringResource(R.string.seerr_sign_out)) },
					enabled = !busy,
					onClick = {
						busy = true
						scope.launch {
							seerr.signOut()
							busy = false
						}
					},
				)
			}
		}

		error?.let { message ->
			item { ListSection(headingContent = { Text(message, color = JellyfinTheme.colorScheme.recording) }) }
		}
	}
}
