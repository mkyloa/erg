package org.jellyfin.androidtv.seerr

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jellyfin.androidtv.auth.repository.UserRepository
import java.io.IOException
import java.util.concurrent.TimeUnit

class SeerrException(message: String, val code: Int = 0) : IOException(message)

/**
 * Talks to a Seerr server. Authentication is a `connect.sid` session cookie obtained through
 * Jellyfin Quick Connect or Jellyfin/local credentials (Seerr does not accept Jellyfin tokens).
 *
 * The server URL is shared by everyone on this device; the session is stored per Jellyfin user
 * so requests are attributed to whoever is signed in to the app.
 */
class SeerrClient(
	context: Context,
	private val userRepository: UserRepository,
) {
	private val prefs = context.getSharedPreferences("seerr", Context.MODE_PRIVATE)
	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
		coerceInputValues = true
	}
	private val http = OkHttpClient.Builder()
		.connectTimeout(10, TimeUnit.SECONDS)
		.readTimeout(20, TimeUnit.SECONDS)
		.followRedirects(true)
		.build()

	private val _signedInUser = MutableStateFlow<SeerrUser?>(null)
	/** Seerr account of the current Jellyfin user, once [refreshUser] or a sign-in has run. */
	val signedInUser: StateFlow<SeerrUser?> = _signedInUser.asStateFlow()

	// --- Configuration ----------------------------------------------------------------------

	var serverUrl: String?
		get() = prefs.getString(KEY_URL, null)
		set(value) {
			val normalized = value?.let(::normalizeUrl)
			if (normalized != serverUrl) {
				// A different server invalidates every stored session
				prefs.edit {
					prefs.all.keys.filter { it.startsWith(KEY_COOKIE_PREFIX) }.forEach(::remove)
					putString(KEY_URL, normalized)
				}
				_signedInUser.value = null
			}
		}

	private val sessionKey: String?
		get() = userRepository.currentUser.value?.id?.let { "$KEY_COOKIE_PREFIX$it" }

	private var sessionCookie: String?
		get() = sessionKey?.let { prefs.getString(it, null) }
		set(value) {
			val key = sessionKey ?: return
			prefs.edit { if (value == null) remove(key) else putString(key, value) }
		}

	val isConfigured get() = !serverUrl.isNullOrBlank()
	val hasSession get() = isConfigured && sessionCookie != null

	// --- Auth -------------------------------------------------------------------------------

	suspend fun quickConnectInitiate(): SeerrQuickConnectInit =
		post("auth/jellyfin/quickconnect/initiate", "{}", authenticated = false)

	/** @return true once the code was approved in Jellyfin, false while waiting. Throws when expired. */
	suspend fun quickConnectCheck(secret: String): Boolean =
		get<SeerrQuickConnectCheck>("auth/jellyfin/quickconnect/check", "secret" to secret, authenticated = false).authenticated

	suspend fun quickConnectAuthenticate(secret: String): SeerrUser =
		signIn("auth/jellyfin/quickconnect/authenticate", """{"secret":${json.encodeToString(secret)}}""")

	suspend fun signInJellyfin(username: String, password: String): SeerrUser =
		signIn(
			"auth/jellyfin",
			"""{"username":${json.encodeToString(username)},"password":${json.encodeToString(password)}}""",
		)

	suspend fun signInLocal(email: String, password: String): SeerrUser =
		signIn(
			"auth/local",
			"""{"email":${json.encodeToString(email)},"password":${json.encodeToString(password)}}""",
		)

	suspend fun signOut() {
		runCatching { post<SeerrError>("auth/logout", "{}") }
		sessionCookie = null
		_signedInUser.value = null
	}

	/** Re-validates the stored session. Clears it when the server rejects it. */
	suspend fun refreshUser(): SeerrUser? {
		if (!hasSession) {
			_signedInUser.value = null
			return null
		}
		return try {
			get<SeerrUser>("auth/me").also { _signedInUser.value = it }
		} catch (err: SeerrException) {
			if (err.code == 401 || err.code == 403) {
				sessionCookie = null
				_signedInUser.value = null
			}
			throw err
		}
	}

	private suspend fun signIn(path: String, body: String): SeerrUser = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(url(path))
			.post(body.toRequestBody(JSON))
			.build()
		http.newCall(request).execute().use { response ->
			val text = response.checkSuccess()
			val cookie = response.headers("Set-Cookie")
				.firstOrNull { it.startsWith("$COOKIE_NAME=") }
				?.substringBefore(';')
				?: throw SeerrException("Seerr did not return a session")
			sessionCookie = cookie
			json.decodeFromString<SeerrUser>(text).also { _signedInUser.value = it }
		}
	}

	// --- Browse -----------------------------------------------------------------------------

	suspend fun trending(page: Int = 1): SeerrPage = get("discover/trending", "page" to page.toString())
	suspend fun popularMovies(page: Int = 1): SeerrPage = get("discover/movies", "page" to page.toString())
	suspend fun popularTv(page: Int = 1): SeerrPage = get("discover/tv", "page" to page.toString())
	suspend fun upcomingMovies(page: Int = 1): SeerrPage = get("discover/movies/upcoming", "page" to page.toString())
	suspend fun upcomingTv(page: Int = 1): SeerrPage = get("discover/tv/upcoming", "page" to page.toString())
	suspend fun search(query: String, page: Int = 1): SeerrPage =
		get("search", "query" to query, "page" to page.toString())

	suspend fun genres(isMovie: Boolean): List<SeerrGenreSlider> = get(if (isMovie) "discover/genreslider/movie" else "discover/genreslider/tv")
	suspend fun genre(isMovie: Boolean, id: Int, page: Int = 1): SeerrPage =
		get(if (isMovie) "discover/movies/genre/$id" else "discover/tv/genre/$id", "page" to page.toString())
	suspend fun network(id: Int, page: Int = 1): SeerrPage = get("discover/tv/network/$id", "page" to page.toString())
	suspend fun studio(id: Int, page: Int = 1): SeerrPage = get("discover/movies/studio/$id", "page" to page.toString())

	suspend fun movie(id: Int): SeerrDetails = get("movie/$id")
	suspend fun tv(id: Int): SeerrDetails = get("tv/$id")

	private fun mediaPath(isMovie: Boolean, id: Int) = if (isMovie) "movie/$id" else "tv/$id"
	suspend fun recommendations(isMovie: Boolean, id: Int): SeerrPage = get("${mediaPath(isMovie, id)}/recommendations")
	suspend fun similar(isMovie: Boolean, id: Int): SeerrPage = get("${mediaPath(isMovie, id)}/similar")

	/** Movies get Rotten Tomatoes and IMDb; shows only Rotten Tomatoes. 404 when there are none. */
	suspend fun ratings(isMovie: Boolean, id: Int): SeerrRatings =
		if (isMovie) get("movie/$id/ratingscombined")
		else SeerrRatings(rt = get<SeerrRtRating>("tv/$id/ratings"))

	suspend fun person(id: Int): SeerrPerson = get("person/$id")
	suspend fun personCredits(id: Int): SeerrPersonCredits = get("person/$id/combined_credits")

	// --- Requests ---------------------------------------------------------------------------

	suspend fun requestMovie(tmdbId: Int, options: SeerrRequestOptions = SeerrRequestOptions()): SeerrMediaRequest =
		post("request", json.encodeToString(requestBody(MEDIA_MOVIE, tmdbId, null, options)))

	suspend fun requestTv(tmdbId: Int, seasons: List<Int>, options: SeerrRequestOptions = SeerrRequestOptions()): SeerrMediaRequest =
		post("request", json.encodeToString(requestBody(MEDIA_TV, tmdbId, seasons, options)))

	private fun requestBody(type: String, tmdbId: Int, seasons: List<Int>?, options: SeerrRequestOptions) = SeerrRequestBody(
		mediaType = type,
		mediaId = tmdbId,
		seasons = seasons,
		is4k = options.is4k,
		serverId = options.serverId,
		profileId = options.profileId,
		rootFolder = options.rootFolder,
	)

	/** Requests, newest first. [filter] is all, pending, approved, processing, available, unavailable or failed. */
	suspend fun requests(filter: String = "all", take: Int = 20, skip: Int = 0, requestedBy: Int? = null): SeerrRequestPage =
		get(
			"request",
			*listOfNotNull(
				"take" to take.toString(),
				"skip" to skip.toString(),
				"filter" to filter,
				"sort" to "added",
				requestedBy?.let { "requestedBy" to it.toString() },
			).toTypedArray(),
		)

	suspend fun approveRequest(id: Int): SeerrMediaRequest = post("request/$id/approve", "{}")
	suspend fun declineRequest(id: Int): SeerrMediaRequest = post("request/$id/decline", "{}")

	/** Radarr (movies) or Sonarr (shows) servers set up in Seerr. */
	suspend fun services(isMovie: Boolean): List<SeerrService> = get(if (isMovie) "service/radarr" else "service/sonarr")
	suspend fun serviceDetails(isMovie: Boolean, id: Int): SeerrServiceDetails =
		get(if (isMovie) "service/radarr/$id" else "service/sonarr/$id")

	// --- Watchlist --------------------------------------------------------------------------

	suspend fun watchlist(page: Int = 1): SeerrWatchlistPage = get("discover/watchlist", "page" to page.toString())

	suspend fun addToWatchlist(tmdbId: Int, isMovie: Boolean, title: String) {
		post<kotlinx.serialization.json.JsonElement>(
			"watchlist",
			json.encodeToString(SeerrWatchlistBody(tmdbId, if (isMovie) MEDIA_MOVIE else MEDIA_TV, title)),
		)
	}

	suspend fun removeFromWatchlist(tmdbId: Int, isMovie: Boolean) = withContext(Dispatchers.IO) {
		val url = url("watchlist/$tmdbId").newBuilder().addQueryParameter("mediaType", if (isMovie) MEDIA_MOVIE else MEDIA_TV).build()
		executeRaw(Request.Builder().url(url).delete())
	}

	// --- Plumbing ---------------------------------------------------------------------------

	private suspend inline fun <reified T> get(
		path: String,
		vararg query: Pair<String, String>,
		authenticated: Boolean = true,
	): T = withContext(Dispatchers.IO) {
		val url = url(path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
		execute(Request.Builder().url(url).get(), authenticated)
	}

	private suspend inline fun <reified T> post(
		path: String,
		body: String,
		authenticated: Boolean = true,
	): T = withContext(Dispatchers.IO) {
		execute(Request.Builder().url(url(path)).post(body.toRequestBody(JSON)), authenticated)
	}

	private inline fun <reified T> execute(builder: Request.Builder, authenticated: Boolean): T {
		if (authenticated) {
			val cookie = sessionCookie ?: throw SeerrException("Not signed in to Seerr", 401)
			builder.header("Cookie", cookie)
		}
		builder.header("Accept", "application/json")
		return http.newCall(builder.build()).execute().use { response ->
			val text = response.checkSuccess()
			json.decodeFromString<T>(text.ifBlank { "{}" })
		}
	}

	private fun executeRaw(builder: Request.Builder) {
		val cookie = sessionCookie ?: throw SeerrException("Not signed in to Seerr", 401)
		builder.header("Cookie", cookie).header("Accept", "application/json")
		http.newCall(builder.build()).execute().use { it.checkSuccess() }
	}

	private fun Response.checkSuccess(): String {
		val text = body?.string().orEmpty()
		if (!isSuccessful) {
			if (code == 401) {
				sessionCookie = null
				_signedInUser.value = null
			}
			val message = runCatching { json.decodeFromString<SeerrError>(text) }.getOrNull()
				?.let { it.message ?: it.error }
				?: when (code) {
					401 -> "Your Seerr session expired. Sign in again in Settings."
					403 -> "Your Seerr account isn't allowed to do that."
					404 -> "Not found on Seerr."
					else -> "Seerr returned HTTP $code"
				}
			throw SeerrException(message, code)
		}
		return text
	}

	private fun url(path: String): HttpUrl {
		val base = serverUrl?.toHttpUrlOrNull() ?: throw SeerrException("Seerr server is not set up")
		return base.newBuilder().addPathSegments("api/v1/$path").build()
	}

	companion object {
		private const val KEY_URL = "server_url"
		private const val KEY_COOKIE_PREFIX = "session_"
		private const val COOKIE_NAME = "connect.sid"
		private val JSON = "application/json".toMediaType()

		/** Accepts "seerr.local:5055" or a full URL; strips trailing slashes and a pasted /api/v1. */
		fun normalizeUrl(input: String): String? {
			val trimmed = input.trim().trimEnd('/').removeSuffix("/api/v1").trimEnd('/')
			if (trimmed.isEmpty()) return null
			val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
			return withScheme.toHttpUrlOrNull()?.toString()?.trimEnd('/')
		}
	}
}
