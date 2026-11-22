package org.jellyfin.androidtv.ui.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.util.UUID

/**
 * Remembers the audio and subtitle tracks picked for a series, per Jellyfin user, across sessions.
 *
 * Tracks are matched by their characteristics rather than by index, because episodes rarely share
 * track layouts: language first, then forced / SDH / commentary flags, title, codec and channels.
 * That way "English SDH" stays SDH and an English commentary track isn't picked over the main one.
 */
class TrackMemory internal constructor(
	private val prefs: SharedPreferences,
	private val currentUserId: () -> UUID?,
) {
	constructor(context: Context, userRepository: UserRepository) : this(
		context.getSharedPreferences("track_memory", Context.MODE_PRIVATE),
		{ userRepository.currentUser.value?.id },
	)

	/** What a remembered track looked like. */
	@Serializable
	data class TrackTraits(
		val language: String? = null,
		val title: String? = null,
		val forced: Boolean = false,
		val hearingImpaired: Boolean = false,
		val commentary: Boolean = false,
		val codec: String? = null,
		val channels: Int? = null,
	)

	@Serializable
	private data class SeriesChoice(
		val audio: TrackTraits? = null,
		val subtitle: TrackTraits? = null,
		/** True when the user turned subtitles off. */
		val subtitlesOff: Boolean = false,
	)

	sealed interface SubtitlePick {
		data object NoPreference : SubtitlePick
		data object Off : SubtitlePick
		data class Track(val index: Int) : SubtitlePick
	}

	private val json = Json { ignoreUnknownKeys = true }

	private fun key(item: BaseItemDto?): String? {
		val series = item?.seriesId ?: return null
		val user = currentUserId() ?: return null
		return "$user:$series"
	}

	private fun load(item: BaseItemDto?): SeriesChoice? {
		val key = key(item) ?: return null
		val raw = prefs.getString(key, null) ?: return null
		return runCatching { json.decodeFromString<SeriesChoice>(raw) }
			.onFailure { Timber.w(it, "Ignoring unreadable track memory") }
			.getOrNull()
	}

	private fun save(item: BaseItemDto?, update: (SeriesChoice) -> SeriesChoice) {
		val key = key(item) ?: return
		val next = update(load(item) ?: SeriesChoice())
		prefs.edit { putString(key, json.encodeToString(next)) }
	}

	// --- Remember -------------------------------------------------------------------------

	fun rememberAudio(item: BaseItemDto?, stream: MediaStream?) {
		if (stream?.type != MediaStreamType.AUDIO) return
		save(item) { it.copy(audio = stream.traits()) }
	}

	/** [stream] null means subtitles were turned off. */
	fun rememberSubtitle(item: BaseItemDto?, stream: MediaStream?) {
		if (stream != null && stream.type != MediaStreamType.SUBTITLE) return
		save(item) { it.copy(subtitle = stream?.traits(), subtitlesOff = stream == null) }
	}

	// --- Recall ---------------------------------------------------------------------------

	fun preferredAudioIndex(item: BaseItemDto?, streams: List<MediaStream>?): Int? {
		val wanted = load(item)?.audio ?: return null
		return bestMatch(wanted, streams.orEmpty().filter { it.type == MediaStreamType.AUDIO })?.index
	}

	fun preferredSubtitle(item: BaseItemDto?, streams: List<MediaStream>?): SubtitlePick {
		val choice = load(item) ?: return SubtitlePick.NoPreference
		if (choice.subtitlesOff) return SubtitlePick.Off
		val wanted = choice.subtitle ?: return SubtitlePick.NoPreference
		val match = bestMatch(wanted, streams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE })
		// Nothing in that language: fall back to the server's default rather than forcing them off
		return match?.let { SubtitlePick.Track(it.index) } ?: SubtitlePick.NoPreference
	}

	companion object {
		internal fun MediaStream.traits() = TrackTraits(
			language = language?.lowercase(),
			title = (title ?: displayTitle)?.trim(),
			forced = isForced,
			hearingImpaired = isHearingImpaired || (title ?: displayTitle).orEmpty().contains("sdh", ignoreCase = true),
			commentary = (title ?: displayTitle).orEmpty().contains("comment", ignoreCase = true),
			codec = codec?.lowercase(),
			channels = channels,
		)

		/** The candidate most like [wanted]; requires the same language when one was remembered. */
		internal fun bestMatch(wanted: TrackTraits, candidates: List<MediaStream>): MediaStream? =
			candidates
				.map { it to it.traits() }
				.filter { (_, traits) -> wanted.language == null || traits.language == wanted.language }
				.maxByOrNull { (stream, traits) ->
					var score = 0
					if (traits.commentary == wanted.commentary) score += 8
					if (traits.forced == wanted.forced) score += 6
					if (traits.hearingImpaired == wanted.hearingImpaired) score += 4
					if (wanted.title != null && traits.title.equals(wanted.title, ignoreCase = true)) score += 3
					if (wanted.codec != null && traits.codec == wanted.codec) score += 1
					if (wanted.channels != null && traits.channels == wanted.channels) score += 1
					// Prefer the track the file marks as default when otherwise tied
					score * 2 + if (stream.isDefault) 1 else 0
				}
				?.first
	}
}
