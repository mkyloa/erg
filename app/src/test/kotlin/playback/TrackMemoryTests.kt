package org.jellyfin.androidtv.ui.playback

import android.content.SharedPreferences
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

private class Prefs : SharedPreferences {
	val map = mutableMapOf<String, Any?>()
	override fun getAll() = map.toMutableMap()
	override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
	override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
	override fun getInt(key: String, defValue: Int) = defValue
	override fun getLong(key: String, defValue: Long) = defValue
	override fun getFloat(key: String, defValue: Float) = defValue
	override fun getBoolean(key: String, defValue: Boolean) = defValue
	override fun contains(key: String) = key in map
	override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
	override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
	override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
		val pending = mutableMapOf<String, Any?>()
		override fun putString(key: String, value: String?) = apply { pending[key] = value }
		override fun putStringSet(key: String, values: MutableSet<String>?) = this
		override fun putInt(key: String, value: Int) = this
		override fun putLong(key: String, value: Long) = this
		override fun putFloat(key: String, value: Float) = this
		override fun putBoolean(key: String, value: Boolean) = this
		override fun remove(key: String) = apply { pending[key] = null }
		override fun clear() = this
		override fun commit(): Boolean { apply(); return true }
		override fun apply() { map.putAll(pending) }
	}
}

private fun stream(
	index: Int,
	type: MediaStreamType,
	language: String?,
	title: String? = null,
	forced: Boolean = false,
	sdh: Boolean = false,
	codec: String? = null,
	channels: Int? = null,
	default: Boolean = false,
) = mockk<MediaStream>(relaxed = true) {
	every { this@mockk.index } returns index
	every { this@mockk.type } returns type
	every { this@mockk.language } returns language
	every { this@mockk.title } returns title
	every { displayTitle } returns title
	every { isForced } returns forced
	every { isHearingImpaired } returns sdh
	every { this@mockk.codec } returns codec
	every { this@mockk.channels } returns channels
	every { isDefault } returns default
}

private fun episode(series: UUID?) = mockk<BaseItemDto>(relaxed = true) { every { seriesId } returns series }

class TrackMemoryTests : FunSpec({
	val a = MediaStreamType.AUDIO
	val s = MediaStreamType.SUBTITLE

	test("remembers audio by traits, not index, across episodes") {
		var user: UUID? = UUID.randomUUID()
		val memory = TrackMemory(Prefs()) { user }
		val series = UUID.randomUUID()

		val ep1 = listOf(stream(1, a, "eng", "English 5.1", codec = "eac3", channels = 6), stream(2, a, "jpn", "Japanese", codec = "aac", channels = 2))
		memory.rememberAudio(episode(series), ep1[1])

		// Next episode lists the tracks in a different order
		val ep2 = listOf(stream(1, a, "jpn", "Japanese", codec = "aac", channels = 2), stream(2, a, "eng", "English", codec = "eac3", channels = 6))
		memory.preferredAudioIndex(episode(series), ep2) shouldBe 1

		// Another user on the same TV has no memory for this series
		user = UUID.randomUUID()
		memory.preferredAudioIndex(episode(series), ep2).shouldBeNull()
	}

	test("keeps SDH, forced and commentary distinctions") {
		val memory = TrackMemory(Prefs()) { UUID(0, 1) }
		val series = UUID.randomUUID()
		memory.rememberSubtitle(episode(series), stream(3, s, "eng", "English SDH", sdh = true))

		val next = listOf(
			stream(4, s, "eng", "English", default = true),
			stream(5, s, "eng", "English (Forced)", forced = true),
			stream(6, s, "eng", "English SDH", sdh = true),
		)
		memory.preferredSubtitle(episode(series), next) shouldBe TrackMemory.SubtitlePick.Track(6)

		memory.rememberAudio(episode(series), stream(1, a, "eng", "English"))
		val audio = listOf(stream(1, a, "eng", "Director's Commentary"), stream(2, a, "eng", "English"))
		memory.preferredAudioIndex(episode(series), audio) shouldBe 2
	}

	test("subtitles off is remembered; a missing language falls back to the default") {
		val memory = TrackMemory(Prefs()) { UUID(0, 1) }
		val series = UUID.randomUUID()
		memory.rememberSubtitle(episode(series), null)
		memory.preferredSubtitle(episode(series), listOf(stream(4, s, "eng"))) shouldBe TrackMemory.SubtitlePick.Off

		memory.rememberSubtitle(episode(series), stream(4, s, "fra", "Français"))
		memory.preferredSubtitle(episode(series), listOf(stream(9, s, "eng"))) shouldBe TrackMemory.SubtitlePick.NoPreference
	}

	test("movies and items without a series are not remembered") {
		val prefs = Prefs()
		val memory = TrackMemory(prefs) { UUID(0, 1) }
		memory.rememberAudio(episode(null), stream(1, a, "eng"))
		prefs.map.isEmpty() shouldBe true
		memory.preferredAudioIndex(episode(null), listOf(stream(1, a, "eng"))).shouldBeNull()
	}
})
