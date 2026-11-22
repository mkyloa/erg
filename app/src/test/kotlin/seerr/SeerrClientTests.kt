package org.jellyfin.androidtv.seerr

import android.content.Context
import android.content.SharedPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

/** Minimal in-memory SharedPreferences so the client can run on the JVM. */
private class MemoryPrefs : SharedPreferences {
	val map = mutableMapOf<String, Any?>()
	override fun getAll(): MutableMap<String, *> = map.toMutableMap()
	override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
	override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
	override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
	override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
	override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
	override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
	override fun contains(key: String) = key in map
	override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
	override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
	override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
		val pending = mutableMapOf<String, Any?>()
		val removed = mutableSetOf<String>()
		var clear = false
		override fun putString(key: String, value: String?) = apply { pending[key] = value }
		override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
		override fun putInt(key: String, value: Int) = apply { pending[key] = value }
		override fun putLong(key: String, value: Long) = apply { pending[key] = value }
		override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
		override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
		override fun remove(key: String) = apply { removed += key }
		override fun clear() = apply { clear = true }
		override fun commit(): Boolean { apply(); return true }
		override fun apply() {
			if (clear) map.clear()
			removed.forEach(map::remove)
			map.putAll(pending)
		}
	}
}

private class Fixture {
	val server = MockWebServer().apply { start() }
	val prefs = MemoryPrefs()
	val currentUser = MutableStateFlow<UserDto?>(user(UUID.randomUUID()))
	val client = SeerrClient(
		context = mockk<Context> { every { getSharedPreferences(any(), any()) } returns prefs },
		userRepository = mockk<UserRepository> { every { currentUser } returns this@Fixture.currentUser },
	).apply { serverUrl = server.url("/").toString() }

	fun enqueue(body: String, code: Int = 200, vararg headers: Pair<String, String>) = server.enqueue(
		MockResponse.Builder().code(code).body(body).apply {
			addHeader("Content-Type", "application/json")
			headers.forEach { (k, v) -> addHeader(k, v) }
		}.build()
	)

	companion object {
		fun user(id: UUID) = mockk<UserDto>(relaxed = true) { every { this@mockk.id } returns id }
	}
}

private const val USER_JSON = """{"id":7,"email":"m@example.com","username":"mk","permissions":32,"requestCount":2}"""

class SeerrClientTests : FunSpec({
	test("normalizeUrl adds a scheme and strips trailing slashes and /api/v1") {
		SeerrClient.normalizeUrl("seerr.local:5055") shouldBe "http://seerr.local:5055"
		SeerrClient.normalizeUrl(" https://req.example.com/ ") shouldBe "https://req.example.com"
		SeerrClient.normalizeUrl("http://10.0.0.5:5055/api/v1/") shouldBe "http://10.0.0.5:5055"
		SeerrClient.normalizeUrl("").shouldBeNull()
	}

	test("password sign-in stores the session cookie and sends it afterwards") {
		val f = Fixture()
		f.enqueue(USER_JSON, 200, "Set-Cookie" to "connect.sid=s%3Aabc.def; Path=/; HttpOnly")
		val user = f.client.signInJellyfin("mk", "pw\"with\"quotes")
		user.id shouldBe 7
		f.client.hasSession shouldBe true

		val login = f.server.takeRequest()
		login.url.encodedPath shouldBe "/api/v1/auth/jellyfin"
		login.body!!.utf8() shouldBe """{"username":"mk","password":"pw\"with\"quotes"}"""

		f.enqueue("""{"page":1,"totalPages":3,"totalResults":2,"results":[
			{"id":1,"mediaType":"movie","title":"Dune: Part Two","releaseDate":"2024-02-27","posterPath":"/p.jpg","mediaInfo":{"status":5,"jellyfinMediaId":"abc"}},
			{"id":2,"mediaType":"tv","name":"Shogun","firstAirDate":"2024-02-27","unknownField":true},
			{"id":3,"mediaType":"person","profilePath":"/x.jpg"}
		]}""")
		val page = f.client.trending()
		f.server.takeRequest().headers["Cookie"] shouldBe "connect.sid=s%3Aabc.def"
		page.totalPages shouldBe 3
		page.results[0].displayTitle shouldBe "Dune: Part Two"
		page.results[0].year shouldBe "2024"
		page.results[0].status shouldBe SeerrMediaStatus.AVAILABLE
		page.results[1].displayTitle shouldBe "Shogun"
		page.results[1].isTv shouldBe true
		page.results[1].status shouldBe SeerrMediaStatus.UNKNOWN
	}

	test("sessions are per Jellyfin user") {
		val f = Fixture()
		f.enqueue(USER_JSON, 200, "Set-Cookie" to "connect.sid=one; Path=/")
		f.client.signInJellyfin("a", "b")
		f.client.hasSession shouldBe true

		f.currentUser.value = Fixture.user(UUID.randomUUID())
		f.client.hasSession shouldBe false
	}

	test("changing the server clears stored sessions") {
		val f = Fixture()
		f.enqueue(USER_JSON, 200, "Set-Cookie" to "connect.sid=one; Path=/")
		f.client.signInJellyfin("a", "b")
		f.client.serverUrl = "http://other-host:5055"
		f.client.hasSession shouldBe false
	}

	test("a 401 drops the session and surfaces a helpful error") {
		val f = Fixture()
		f.enqueue(USER_JSON, 200, "Set-Cookie" to "connect.sid=one; Path=/")
		f.client.signInJellyfin("a", "b")
		f.enqueue("""{"message":"Unauthorized"}""", 401)
		val err = shouldThrow<SeerrException> { f.client.refreshUser() }
		err.code shouldBe 401
		f.client.hasSession shouldBe false
		f.client.signedInUser.value.shouldBeNull()
	}

	test("sign-in without a session cookie fails") {
		val f = Fixture()
		f.enqueue(USER_JSON)
		shouldThrow<SeerrException> { f.client.signInLocal("m@example.com", "pw") }
		f.client.hasSession shouldBe false
	}

	test("quick connect flow") {
		val f = Fixture()
		f.enqueue("""{"code":"123456","secret":"s3cret"}""")
		val init = f.client.quickConnectInitiate()
		init.code shouldBe "123456"
		f.server.takeRequest().headers["Cookie"].shouldBeNull()

		f.enqueue("""{"authenticated":false}""")
		f.client.quickConnectCheck(init.secret) shouldBe false
		f.server.takeRequest().url.queryParameter("secret") shouldBe "s3cret"

		f.enqueue("", 404)
		shouldThrow<SeerrException> { f.client.quickConnectCheck(init.secret) }.code shouldBe 404

		f.enqueue(USER_JSON, 200, "Set-Cookie" to "connect.sid=qc; Path=/")
		f.client.quickConnectAuthenticate(init.secret).username shouldBe "mk"
		f.server.takeRequest()
		f.server.takeRequest().body!!.utf8() shouldBe """{"secret":"s3cret"}"""
		f.client.hasSession shouldBe true
	}

	test("request bodies for movies and seasons") {
		val f = Fixture()
		f.enqueue(USER_JSON, 200, "Set-Cookie" to "connect.sid=one; Path=/")
		f.client.signInJellyfin("a", "b")
		f.server.takeRequest()

		f.enqueue("""{"id":11,"status":1}""", 201)
		f.client.requestMovie(693134).id shouldBe 11
		f.server.takeRequest().body!!.utf8() shouldBe """{"mediaType":"movie","mediaId":693134}"""

		f.enqueue("""{"id":12,"status":2}""", 201)
		f.client.requestTv(126308, listOf(1, 2)).status shouldBe SeerrRequestStatus.APPROVED
		f.server.takeRequest().body!!.utf8() shouldBe """{"mediaType":"tv","mediaId":126308,"seasons":[1,2]}"""

		f.enqueue("""{"message":"Request quota exceeded"}""", 403)
		shouldThrow<SeerrException> { f.client.requestMovie(1) }.message shouldContain "quota"
	}

	test("tv details: season status from media info and open requests") {
		val f = Fixture()
		f.enqueue(USER_JSON, 200, "Set-Cookie" to "connect.sid=one; Path=/")
		f.client.signInJellyfin("a", "b")
		f.enqueue("""{"id":126308,"name":"Shogun","firstAirDate":"2024-02-27","episodeRunTime":[59],
			"genres":[{"id":18,"name":"Drama"}],
			"seasons":[{"seasonNumber":0,"name":"Specials"},{"seasonNumber":1,"episodeCount":10},{"seasonNumber":2},{"seasonNumber":3}],
			"mediaInfo":{"status":4,"seasons":[{"seasonNumber":1,"status":5}],
				"requests":[{"id":1,"status":2,"seasons":[{"seasonNumber":2}]},{"id":2,"status":3,"seasons":[{"seasonNumber":3}]}]}}""")
		val details = f.client.tv(126308)
		details.displayTitle shouldBe "Shogun"
		details.runtimeMinutes shouldBe 59
		details.status shouldBe SeerrMediaStatus.PARTIALLY_AVAILABLE
		details.requestableSeasons.map { it.seasonNumber } shouldBe listOf(1, 2, 3)
		details.seasonStatus(1) shouldBe SeerrMediaStatus.AVAILABLE
		details.seasonStatus(2) shouldBe SeerrMediaStatus.PENDING
		// Declined requests don't block re-requesting
		details.seasonStatus(3) shouldBe SeerrMediaStatus.UNKNOWN
	}

	test("image urls") {
		seerrImageUrl("/abc.jpg") shouldBe "https://image.tmdb.org/t/p/w500/abc.jpg"
		seerrImageUrl(null).shouldBeNull()
		seerrImageUrl("/b.jpg", "w1280").shouldNotBeNull() shouldContain "w1280"
	}
})
