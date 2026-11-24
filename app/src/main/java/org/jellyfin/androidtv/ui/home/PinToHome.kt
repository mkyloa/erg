package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.widget.Toast
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.java.KoinJavaComponent.get

/** "Pin to home" for collections and playlists, from the screens that show them. */
object PinToHome {
	private fun typeOf(item: BaseItemDto?) = when (item?.type) {
		BaseItemKind.BOX_SET -> PinnedRowType.COLLECTION
		BaseItemKind.PLAYLIST -> PinnedRowType.PLAYLIST
		else -> null
	}

	/** Ids are stored with or without dashes depending on where they came from. */
	private fun String.normalizedId() = replace("-", "").lowercase()

	private fun indexOf(item: BaseItemDto): Int {
		val type = typeOf(item) ?: return -1
		val id = item.id.toString().normalizedId()
		return PinnedRows.load(get(UserSettingPreferences::class.java))
			.indexOfFirst { it.type == type && it.id.normalizedId() == id }
	}

	@JvmStatic
	fun canPin(item: BaseItemDto?) = typeOf(item) != null

	@JvmStatic
	fun isPinned(item: BaseItemDto?) = item != null && indexOf(item) >= 0

	@JvmStatic
	fun label(context: Context, item: BaseItemDto?) =
		context.getString(if (isPinned(item)) R.string.erg_unpin_from_home else R.string.erg_pin_to_home)

	/** Pins or unpins [item], says so in a toast, and returns whether it is now pinned. */
	@JvmStatic
	fun toggle(context: Context, item: BaseItemDto): Boolean {
		val type = typeOf(item) ?: return false
		val prefs = get<UserSettingPreferences>(UserSettingPreferences::class.java)
		val name = item.name.orEmpty()
		val index = indexOf(item)
		return if (index >= 0) {
			PinnedRows.remove(prefs, index)
			Toast.makeText(context, context.getString(R.string.erg_unpinned_from_home, name), Toast.LENGTH_SHORT).show()
			false
		} else {
			PinnedRows.add(prefs, PinnedRow(type, item.id.toString(), name))
			Toast.makeText(context, context.getString(R.string.erg_pinned_to_home, name), Toast.LENGTH_SHORT).show()
			true
		}
	}
}
