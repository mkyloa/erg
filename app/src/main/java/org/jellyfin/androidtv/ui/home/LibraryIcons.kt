package org.jellyfin.androidtv.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import timber.log.Timber

/** Erg: how a library shows in the home top bar. [AUTO] picks an icon from the library type. */
enum class LibraryIcon(@StringRes val nameRes: Int, @DrawableRes val icon: Int?) {
	AUTO(R.string.erg_library_icon_auto, null),
	TEXT(R.string.erg_library_icon_text, null),
	MOVIE(R.string.erg_library_icon_movie, R.drawable.ic_movie),
	CLAPPERBOARD(R.string.erg_library_icon_clapperboard, R.drawable.ic_clapperboard),
	TV(R.string.erg_library_icon_tv, R.drawable.ic_tv),
	COLLECTION(R.string.erg_library_icon_collection, R.drawable.ic_grid),
	MUSIC(R.string.erg_library_icon_music, R.drawable.ic_music_album),
	MICROPHONE(R.string.erg_library_icon_microphone, R.drawable.ic_mic),
	PHOTOS(R.string.erg_library_icon_photos, R.drawable.ic_photos),
	CAMERA(R.string.erg_library_icon_camera, R.drawable.ic_camera),
	LIVE(R.string.erg_library_icon_live, R.drawable.ic_live),
	FOLDER(R.string.erg_library_icon_folder, R.drawable.ic_folder),
	STAR(R.string.erg_library_icon_star, R.drawable.ic_star),
	HEART(R.string.erg_library_icon_heart, R.drawable.ic_heart),
}

/** Erg: per-library top bar icons, saved in the server-synced user settings like pinned rows. */
object LibraryIcons {
	private val json = Json { ignoreUnknownKeys = true }

	/** Bumped on every change so the top bar re-reads the choices. */
	val revision = mutableIntStateOf(0)

	fun load(prefs: UserSettingPreferences): Map<String, LibraryIcon> {
		val raw = prefs[prefs.libraryIcons].takeIf { it.isNotBlank() } ?: return emptyMap()
		return runCatching { json.decodeFromString<Map<String, String>>(raw) }
			.onFailure { Timber.w(it, "Unreadable library icons setting") }
			.getOrDefault(emptyMap())
			.mapNotNull { (id, name) -> LibraryIcon.entries.firstOrNull { it.name == name }?.let { id to it } }
			.toMap()
	}

	fun set(prefs: UserSettingPreferences, libraryId: String, icon: LibraryIcon) {
		val icons = load(prefs).toMutableMap()
		if (icon == LibraryIcon.AUTO) icons.remove(libraryId) else icons[libraryId] = icon
		prefs[prefs.libraryIcons] = json.encodeToString(icons.mapValues { it.value.name })
		revision.intValue++
		// User settings live on the server; without a commit the change is gone after a restart
		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
			if (!prefs.commit()) Timber.w("Library icons weren't saved to the server")
		}
	}

	/** The icon AUTO picks for a library type. */
	fun automatic(collectionType: CollectionType?): LibraryIcon = when (collectionType) {
		CollectionType.MOVIES -> LibraryIcon.MOVIE
		CollectionType.TVSHOWS -> LibraryIcon.TV
		CollectionType.BOXSETS -> LibraryIcon.COLLECTION
		CollectionType.MUSIC, CollectionType.MUSICVIDEOS -> LibraryIcon.MUSIC
		CollectionType.PHOTOS, CollectionType.HOMEVIDEOS -> LibraryIcon.PHOTOS
		CollectionType.LIVETV -> LibraryIcon.LIVE
		else -> LibraryIcon.FOLDER
	}

	/**
	 * The icon each library shows (null = text tab). Libraries left on AUTO that would share an
	 * icon with another AUTO library (two movie libraries, say) stay text so they can be told apart.
	 */
	fun resolve(libraries: List<BaseItemDto>, choices: Map<String, LibraryIcon>): Map<String, Int?> {
		val autoIcons = libraries
			.filter { (choices[it.id.toString()] ?: LibraryIcon.AUTO) == LibraryIcon.AUTO }
			.groupBy { automatic(it.collectionType) }
		return libraries.associate { library ->
			val id = library.id.toString()
			val icon = when (val choice = choices[id] ?: LibraryIcon.AUTO) {
				LibraryIcon.AUTO -> automatic(library.collectionType).takeIf { autoIcons[it].orEmpty().size == 1 }?.icon
				else -> choice.icon
			}
			id to icon
		}
	}
}
