package org.jellyfin.androidtv.ui.settings.screen.erg

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.home.PinnedRow
import org.jellyfin.androidtv.ui.home.PinnedRowType
import org.jellyfin.androidtv.ui.home.PinnedRows
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.studiosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.koin.compose.koinInject

internal fun PinnedRowType.labelRes() = when (this) {
	PinnedRowType.COLLECTION -> R.string.erg_pin_collection
	PinnedRowType.PLAYLIST -> R.string.erg_pin_playlist
	PinnedRowType.GENRE -> R.string.erg_pin_genre
	PinnedRowType.STUDIO -> R.string.erg_pin_studio
}

/** Settings → Home → Pinned rows: what's pinned, and buttons to add more. */
@Composable
fun SettingsHomePinnedScreen() {
	val router = LocalRouter.current
	val prefs = koinInject<UserSettingPreferences>()
	val rows = PinnedRows.load(prefs)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_prefs).uppercase()) },
				headingContent = { Text(stringResource(R.string.erg_pinned_rows)) },
				captionContent = { Text(stringResource(R.string.erg_pinned_rows_caption)) },
			)
		}

		if (rows.isEmpty()) item { ListSection(headingContent = { Text(stringResource(R.string.erg_pinned_none)) }) }

		itemsIndexed(rows) { index, row ->
			ListButton(
				headingContent = { Text(row.name) },
				captionContent = { Text(stringResource(row.type.labelRes())) },
				onClick = { router.push(Routes.HOME_PINNED_ROW, mapOf("index" to index.toString())) },
				modifier = Modifier.focusKey("pinned_$index"),
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.erg_pin_add)) }) }

		items(PinnedRowType.entries) { type ->
			ListButton(
				headingContent = { Text(stringResource(type.labelRes())) },
				onClick = { router.push(Routes.HOME_PINNED_ADD, mapOf("type" to type.name)) },
				modifier = Modifier.focusKey("pin_add_${type.name}"),
			)
		}
	}
}

/** Move or remove one pinned row. */
@Composable
fun SettingsHomePinnedRowScreen(index: Int) {
	val router = LocalRouter.current
	val prefs = koinInject<UserSettingPreferences>()
	var current by remember { mutableIntStateOf(index) }
	val rows = PinnedRows.load(prefs)
	val row = rows.getOrNull(current) ?: return ListMessage { Text("Unknown row $index") }

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.erg_pinned_rows).uppercase()) },
				headingContent = { Text(row.name) },
				captionContent = { Text(stringResource(row.type.labelRes())) },
			)
		}

		if (current > 0) item {
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_pin_move_up)) },
				onClick = {
					PinnedRows.move(prefs, current, -1)
					current -= 1
				},
			)
		}

		if (current < rows.lastIndex) item {
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_pin_move_down)) },
				onClick = {
					PinnedRows.move(prefs, current, 1)
					current += 1
				},
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.erg_pin_remove)) },
				onClick = {
					PinnedRows.remove(prefs, current)
					router.back()
				},
			)
		}
	}
}

private sealed interface Choices {
	data object Loading : Choices
	data class Error(val message: String) : Choices
	data class Loaded(val items: List<BaseItemDto>) : Choices
}

/** Pick a collection, playlist, genre or studio from the server to pin. */
@Composable
fun SettingsHomePinnedAddScreen(type: PinnedRowType) {
	val router = LocalRouter.current
	val prefs = koinInject<UserSettingPreferences>()
	val api = koinInject<ApiClient>()
	val userRepository = koinInject<UserRepository>()
	val pinnedIds = remember { PinnedRows.load(prefs).filter { it.type == type }.map { it.id }.toSet() }

	val choices by produceState<Choices>(Choices.Loading, type) {
		value = runCatching {
			withContext(Dispatchers.IO) {
				val userId = userRepository.currentUser.value?.id
				when (type) {
					PinnedRowType.COLLECTION -> api.itemsApi.getItems(
						userId = userId, includeItemTypes = setOf(BaseItemKind.BOX_SET), recursive = true, sortBy = setOf(ItemSortBy.SORT_NAME),
					).content.items
					PinnedRowType.PLAYLIST -> api.itemsApi.getItems(
						userId = userId, includeItemTypes = setOf(BaseItemKind.PLAYLIST), recursive = true, sortBy = setOf(ItemSortBy.SORT_NAME),
					).content.items
					PinnedRowType.GENRE -> api.genresApi.getGenres(userId = userId, sortBy = setOf(ItemSortBy.SORT_NAME)).content.items
					PinnedRowType.STUDIO -> api.studiosApi.getStudios(userId = userId).content.items
				}
			}
		}.fold({ Choices.Loaded(it) }, { Choices.Error(it.message ?: it.toString()) })
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.erg_pinned_rows).uppercase()) },
				headingContent = { Text(stringResource(type.labelRes())) },
			)
		}

		when (val state = choices) {
			Choices.Loading -> item { ListSection(headingContent = { Text(stringResource(R.string.erg_pin_loading)) }) }
			is Choices.Error -> item { ListSection(headingContent = { Text(stringResource(R.string.erg_pin_load_failed, state.message)) }) }
			is Choices.Loaded -> items(state.items, key = { it.id.toString() }) { item ->
				val id = item.id.toString()
				val already = id in pinnedIds
				ListButton(
					headingContent = { Text(item.name.orEmpty()) },
					captionContent = if (already) ({ Text(stringResource(R.string.erg_pin_already)) }) else null,
					enabled = !already,
					onClick = {
						PinnedRows.add(prefs, PinnedRow(type, id, item.name.orEmpty()))
						router.back()
					},
				)
			}
		}
	}
}
