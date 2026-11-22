package org.jellyfin.androidtv.ui.home

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

/** What a pinned home row shows. */
@Serializable
enum class PinnedRowType {
	COLLECTION, PLAYLIST, GENRE, STUDIO,
}

/** A row pinned to the home screen. [name] is cached for display; [id] is the Jellyfin item id. */
@Serializable
data class PinnedRow(
	val type: PinnedRowType,
	val id: String,
	val name: String,
)

/**
 * Reads and writes pinned rows. They're stored as JSON in the server-synced user settings, so
 * pins follow the user to other devices running Erg.
 */
object PinnedRows {
	private val json = Json { ignoreUnknownKeys = true }

	fun load(prefs: UserSettingPreferences): List<PinnedRow> {
		val raw = prefs[prefs.pinnedRows]
		if (raw.isBlank()) return emptyList()
		return runCatching { json.decodeFromString<List<PinnedRow>>(raw) }
			.onFailure { Timber.w(it, "Ignoring unreadable pinned rows") }
			.getOrDefault(emptyList())
	}

	fun save(prefs: UserSettingPreferences, rows: List<PinnedRow>) {
		prefs[prefs.pinnedRows] = json.encodeToString(rows)
	}

	fun add(prefs: UserSettingPreferences, row: PinnedRow) {
		val rows = load(prefs)
		if (rows.any { it.type == row.type && it.id == row.id }) return
		save(prefs, rows + row)
	}

	fun remove(prefs: UserSettingPreferences, index: Int) {
		val rows = load(prefs).toMutableList()
		if (index !in rows.indices) return
		rows.removeAt(index)
		save(prefs, rows)
	}

	fun move(prefs: UserSettingPreferences, index: Int, by: Int) {
		val rows = load(prefs).toMutableList()
		val target = index + by
		if (index !in rows.indices || target !in rows.indices) return
		rows.add(target, rows.removeAt(index))
		save(prefs, rows)
	}

	/** The query behind a pinned row. */
	internal fun query(row: PinnedRow, userId: UUID?): GetItemsRequest? {
		val id = runCatching { UUID.fromString(row.id) }.getOrNull()
			?: row.id.takeIf { it.length == 32 }?.let { hex ->
				runCatching { UUID.fromString(hex.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5")) }.getOrNull()
			}
			?: return null

		val base = GetItemsRequest(
			userId = userId,
			fields = ItemRepository.itemFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			limit = ITEM_LIMIT,
		)
		val titles = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		return when (row.type) {
			// Keep the collection's and playlist's own order
			PinnedRowType.COLLECTION -> base.copy(parentId = id)
			PinnedRowType.PLAYLIST -> base.copy(parentId = id)
			PinnedRowType.GENRE -> base.copy(
				genreIds = setOf(id), includeItemTypes = titles, recursive = true,
				sortBy = setOf(ItemSortBy.DATE_CREATED), sortOrder = setOf(SortOrder.DESCENDING),
			)
			PinnedRowType.STUDIO -> base.copy(
				studioIds = setOf(id), includeItemTypes = titles, recursive = true,
				sortBy = setOf(ItemSortBy.DATE_CREATED), sortOrder = setOf(SortOrder.DESCENDING),
			)
		}
	}

	/** Home rows for everything pinned, in the user's order. */
	fun rows(context: Context, prefs: UserSettingPreferences, userRepository: UserRepository): List<HomeFragmentRow> {
		val userId = userRepository.currentUser.value?.id
		return load(prefs).mapNotNull { row ->
			val query = query(row, userId) ?: return@mapNotNull null
			HomeFragmentBrowseRowDefRow(
				BrowseRowDef(row.name, query, ITEM_LIMIT, false, true, arrayOf(ChangeTriggerType.LibraryUpdated))
			)
		}
	}

	private const val ITEM_LIMIT = 30
}
