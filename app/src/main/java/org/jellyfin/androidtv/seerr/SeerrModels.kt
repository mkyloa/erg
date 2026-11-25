package org.jellyfin.androidtv.seerr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Models for the Seerr (Jellyseerr / Overseerr successor) REST API, `/api/v1`.
 * Only the fields this app uses are declared; unknown fields are ignored when decoding.
 */

@Serializable
data class SeerrUser(
	val id: Int,
	val email: String? = null,
	val username: String? = null,
	val jellyfinUsername: String? = null,
	val displayName: String? = null,
	val permissions: Long = 0,
	val avatar: String? = null,
) {
	val name: String get() = displayName ?: username ?: jellyfinUsername ?: email ?: "#$id"

	/** Admins have every permission. */
	fun can(permission: Long) = permissions and SeerrPermission.ADMIN != 0L || permissions and permission != 0L
	fun canAny(vararg permission: Long) = permission.any(::can)

	val canManageRequests get() = can(SeerrPermission.MANAGE_REQUESTS)
	val canRequestAdvanced get() = canAny(SeerrPermission.REQUEST_ADVANCED, SeerrPermission.MANAGE_REQUESTS)
	fun canRequest4k(isMovie: Boolean) =
		canAny(SeerrPermission.REQUEST_4K, if (isMovie) SeerrPermission.REQUEST_4K_MOVIE else SeerrPermission.REQUEST_4K_TV)
}

/** Seerr permission bits (`server/lib/permissions.ts`). */
object SeerrPermission {
	const val ADMIN = 2L
	const val MANAGE_REQUESTS = 16L
	const val REQUEST_4K = 1024L
	const val REQUEST_4K_MOVIE = 2048L
	const val REQUEST_4K_TV = 4096L
	const val REQUEST_ADVANCED = 8192L
}

@Serializable
data class SeerrQuickConnectInit(
	val code: String,
	val secret: String,
)

@Serializable
data class SeerrQuickConnectCheck(
	val authenticated: Boolean = false,
)

@Serializable
data class SeerrPage(
	val page: Int = 1,
	val totalPages: Int = 1,
	val totalResults: Int = 0,
	val results: List<SeerrResult> = emptyList(),
)

/**
 * A movie, show or person from discover/search results. Movies use [title]/[releaseDate],
 * shows use [name]/[firstAirDate]; [displayTitle] and [year] smooth that over.
 */
@Serializable
data class SeerrResult(
	val id: Int,
	val mediaType: String? = null,
	val title: String? = null,
	val name: String? = null,
	val overview: String? = null,
	val posterPath: String? = null,
	val backdropPath: String? = null,
	val profilePath: String? = null,
	val releaseDate: String? = null,
	val firstAirDate: String? = null,
	val voteAverage: Double? = null,
	val mediaInfo: SeerrMediaInfo? = null,
) {
	val isMovie get() = mediaType == MEDIA_MOVIE
	val isTv get() = mediaType == MEDIA_TV
	val displayTitle get() = title ?: name ?: ""
	val year get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }
	val status get() = SeerrMediaStatus.from(mediaInfo?.status)
}

@Serializable
data class SeerrMediaInfo(
	val id: Int? = null,
	val tmdbId: Int? = null,
	val tvdbId: Int? = null,
	val status: Int? = null,
	val status4k: Int? = null,
	// Present on Jellyfin-backed Seerr instances; links the title to the Jellyfin library item
	val jellyfinMediaId: String? = null,
	val jellyfinMediaId4k: String? = null,
	val mediaType: String? = null,
	val requests: List<SeerrMediaRequest> = emptyList(),
	val seasons: List<SeerrSeasonStatus> = emptyList(),
	val downloadStatus: List<SeerrDownload> = emptyList(),
	val downloadStatus4k: List<SeerrDownload> = emptyList(),
) {
	fun status(is4k: Boolean) = SeerrMediaStatus.from(if (is4k) status4k else status)

	/** Download progress 0..1 across everything still downloading, or null when nothing is. */
	fun downloadProgress(is4k: Boolean): Float? {
		val downloads = (if (is4k) downloadStatus4k else downloadStatus).filter { (it.size ?: 0.0) > 0 }
		if (downloads.isEmpty()) return null
		val size = downloads.sumOf { it.size ?: 0.0 }
		val left = downloads.sumOf { it.sizeLeft ?: 0.0 }
		return (1 - left / size).toFloat().coerceIn(0f, 1f)
	}

	/** The latest estimated completion time of the active downloads (ISO date). */
	fun downloadEta(is4k: Boolean) = (if (is4k) downloadStatus4k else downloadStatus)
		.mapNotNull { it.estimatedCompletionTime }.maxOrNull()
}

/** One download in Sonarr/Radarr's queue. Sizes are bytes. */
@Serializable
data class SeerrDownload(
	val title: String? = null,
	val size: Double? = null,
	val sizeLeft: Double? = null,
	val estimatedCompletionTime: String? = null,
)

@Serializable
data class SeerrSeasonStatus(
	val seasonNumber: Int,
	val status: Int? = null,
	val status4k: Int? = null,
)

@Serializable
data class SeerrMediaRequest(
	val id: Int,
	val status: Int? = null,
	val is4k: Boolean = false,
	val seasons: List<SeerrRequestSeason> = emptyList(),
	val type: String? = null,
	val createdAt: String? = null,
	val updatedAt: String? = null,
	val media: SeerrMediaInfo? = null,
	val requestedBy: SeerrUser? = null,
)

@Serializable
data class SeerrPageInfo(
	val pages: Int = 1,
	val page: Int = 1,
	val results: Int = 0,
)

@Serializable
data class SeerrRequestPage(
	val pageInfo: SeerrPageInfo = SeerrPageInfo(),
	val results: List<SeerrMediaRequest> = emptyList(),
)

/** A Radarr or Sonarr server as Seerr lists it to requesters. */
@Serializable
data class SeerrService(
	val id: Int,
	val name: String? = null,
	val is4k: Boolean = false,
	val isDefault: Boolean = false,
	val activeProfileId: Int? = null,
	val activeDirectory: String? = null,
)

@Serializable
data class SeerrServiceProfile(
	val id: Int,
	val name: String? = null,
)

@Serializable
data class SeerrServiceDetails(
	val server: SeerrService? = null,
	val profiles: List<SeerrServiceProfile> = emptyList(),
)

/** `/discover/genreslider/{movie,tv}`. */
@Serializable
data class SeerrGenreSlider(
	val id: Int,
	val name: String,
	val backdrops: List<String> = emptyList(),
)

/** Local Seerr watchlist entry (`/discover/watchlist`). */
@Serializable
data class SeerrWatchlistItem(
	val tmdbId: Int? = null,
	val mediaType: String? = null,
	val type: String? = null,
	val title: String? = null,
) {
	val kind get() = mediaType ?: type
}

@Serializable
data class SeerrWatchlistPage(
	val page: Int = 1,
	val totalPages: Int = 1,
	val results: List<SeerrWatchlistItem> = emptyList(),
)

@Serializable
data class SeerrWatchlistBody(
	val tmdbId: Int,
	val mediaType: String,
	val title: String,
)

@Serializable
data class SeerrRequestSeason(
	val seasonNumber: Int,
	val status: Int? = null,
)

@Serializable
data class SeerrGenre(
	val id: Int,
	val name: String,
)

@Serializable
data class SeerrSeason(
	val id: Int? = null,
	val seasonNumber: Int,
	val name: String? = null,
	val episodeCount: Int? = null,
	val airDate: String? = null,
)

/** Union of the movie and tv detail payloads (`/movie/{id}`, `/tv/{id}`). */
@Serializable
data class SeerrDetails(
	val id: Int,
	val title: String? = null,
	val name: String? = null,
	val overview: String? = null,
	val tagline: String? = null,
	val posterPath: String? = null,
	val backdropPath: String? = null,
	val releaseDate: String? = null,
	val firstAirDate: String? = null,
	val runtime: Int? = null,
	val episodeRunTime: List<Int> = emptyList(),
	val voteAverage: Double? = null,
	val genres: List<SeerrGenre> = emptyList(),
	val seasons: List<SeerrSeason> = emptyList(),
	@SerialName("numberOfSeason") val numberOfSeasonsLegacy: Int? = null,
	val numberOfSeasons: Int? = null,
	val externalIds: JsonElement? = null,
	val mediaInfo: SeerrMediaInfo? = null,
	val credits: SeerrCredits? = null,
	val relatedVideos: List<SeerrVideo> = emptyList(),
	val createdBy: List<SeerrPersonRef> = emptyList(),
	// Seerr's own watchlist; null on servers without one
	val onUserWatchlist: Boolean? = null,
) {
	val displayTitle get() = title ?: name ?: ""
	val year get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }

	/** Top-billed cast, in billing order. */
	val cast get() = credits?.cast.orEmpty().sortedBy { it.order ?: Int.MAX_VALUE }.distinctBy { it.id }.take(MAX_CAST)

	/** Directors of a movie, or creators of a show. */
	val makers: List<String>
		get() = createdBy.mapNotNull { it.name }.ifEmpty {
			credits?.crew.orEmpty().filter { it.job == "Director" }.mapNotNull { it.name }
		}.distinct().take(3)

	/** The best YouTube trailer: an official trailer first, then any trailer, then a teaser. */
	val trailer: SeerrVideo?
		get() = relatedVideos.filter { it.site == "YouTube" && !it.key.isNullOrBlank() }.let { videos ->
			videos.firstOrNull { it.type == "Trailer" } ?: videos.firstOrNull { it.type == "Teaser" }
		}

	val runtimeMinutes get() = runtime ?: episodeRunTime.firstOrNull()
	val status get() = SeerrMediaStatus.from(mediaInfo?.status)

	/** Real seasons only; TMDB lists specials as season 0. */
	val requestableSeasons get() = seasons.filter { it.seasonNumber > 0 }

	fun status(is4k: Boolean) = SeerrMediaStatus.from(if (is4k) mediaInfo?.status4k else mediaInfo?.status)

	/** Seasons not in the library and not requested, in the standard or 4K version. */
	fun missingSeasons(is4k: Boolean = false) = requestableSeasons.filter { seasonStatus(it.seasonNumber, is4k).isRequestable }

	/** Status of a single season, from media info or from any open request covering it. */
	fun seasonStatus(seasonNumber: Int, is4k: Boolean = false): SeerrMediaStatus {
		mediaInfo?.seasons?.firstOrNull { it.seasonNumber == seasonNumber }?.let { if (is4k) it.status4k else it.status }?.let {
			val status = SeerrMediaStatus.from(it)
			if (status != SeerrMediaStatus.UNKNOWN) return status
		}
		val requested = mediaInfo?.requests.orEmpty()
			.filter { it.is4k == is4k && it.status != SeerrRequestStatus.DECLINED }
			.any { request -> request.seasons.any { it.seasonNumber == seasonNumber } }
		return if (requested) SeerrMediaStatus.PENDING else SeerrMediaStatus.UNKNOWN
	}
}

@Serializable
data class SeerrCredits(
	val cast: List<SeerrCastMember> = emptyList(),
	val crew: List<SeerrCrewMember> = emptyList(),
)

@Serializable
data class SeerrCastMember(
	val id: Int,
	val name: String? = null,
	val character: String? = null,
	val order: Int? = null,
	val profilePath: String? = null,
)

@Serializable
data class SeerrCrewMember(
	val id: Int,
	val name: String? = null,
	val job: String? = null,
	val profilePath: String? = null,
)

@Serializable
data class SeerrPersonRef(
	val id: Int,
	val name: String? = null,
)

@Serializable
data class SeerrVideo(
	val key: String? = null,
	val name: String? = null,
	val type: String? = null,
	val site: String? = null,
)

/** Rotten Tomatoes scores (`/movie/{id}/ratings`, `/tv/{id}/ratings`). Scores are percentages. */
@Serializable
data class SeerrRtRating(
	val criticsScore: Double? = null,
	val criticsRating: String? = null,
	val audienceScore: Double? = null,
)

/** IMDb score out of 10. */
@Serializable
data class SeerrImdbRating(
	val criticsScore: Double? = null,
)

/** `/movie/{id}/ratingscombined`; shows only have Rotten Tomatoes. */
@Serializable
data class SeerrRatings(
	val rt: SeerrRtRating? = null,
	val imdb: SeerrImdbRating? = null,
) {
	val isEmpty get() = rt?.criticsScore == null && rt?.audienceScore == null && imdb?.criticsScore == null
}

@Serializable
data class SeerrPerson(
	val id: Int,
	val name: String? = null,
	val birthday: String? = null,
	val deathday: String? = null,
	val placeOfBirth: String? = null,
	val knownForDepartment: String? = null,
	val biography: String? = null,
	val profilePath: String? = null,
)

/** A person's movies and shows; each entry is a [SeerrResult] plus their role in it. */
@Serializable
data class SeerrPersonCredits(
	val cast: List<SeerrCredit> = emptyList(),
	val crew: List<SeerrCredit> = emptyList(),
)

@Serializable
data class SeerrCredit(
	val id: Int,
	val mediaType: String? = null,
	val title: String? = null,
	val name: String? = null,
	val posterPath: String? = null,
	val releaseDate: String? = null,
	val firstAirDate: String? = null,
	val voteCount: Int? = null,
	val popularity: Double? = null,
	val character: String? = null,
	val job: String? = null,
	val mediaInfo: SeerrMediaInfo? = null,
) {
	val date get() = (releaseDate ?: firstAirDate).orEmpty()

	fun toResult() = SeerrResult(
		id = id,
		mediaType = mediaType,
		title = title,
		name = name,
		posterPath = posterPath,
		releaseDate = releaseDate,
		firstAirDate = firstAirDate,
		mediaInfo = mediaInfo,
	)
}

@Serializable
data class SeerrRequestBody(
	val mediaType: String,
	val mediaId: Int,
	val seasons: List<Int>? = null,
	val is4k: Boolean = false,
	val serverId: Int? = null,
	val profileId: Int? = null,
	val rootFolder: String? = null,
)

/** Extra choices on a request: 4K, and for power users the server and quality profile. */
data class SeerrRequestOptions(
	val is4k: Boolean = false,
	val serverId: Int? = null,
	val profileId: Int? = null,
	val rootFolder: String? = null,
)

@Serializable
data class SeerrError(
	val message: String? = null,
	val error: String? = null,
)

const val MEDIA_MOVIE = "movie"
const val MEDIA_TV = "tv"
const val MEDIA_PERSON = "person"
private const val MAX_CAST = 20

/** `MediaInfo.status`: 1 unknown, 2 pending, 3 processing, 4 partially available, 5 available, 6 deleted. */
enum class SeerrMediaStatus {
	UNKNOWN, PENDING, PROCESSING, PARTIALLY_AVAILABLE, AVAILABLE, DELETED;

	val isRequestable get() = this == UNKNOWN || this == DELETED
	val isAvailable get() = this == AVAILABLE || this == PARTIALLY_AVAILABLE

	companion object {
		fun from(value: Int?) = when (value) {
			2 -> PENDING
			3 -> PROCESSING
			4 -> PARTIALLY_AVAILABLE
			5 -> AVAILABLE
			6 -> DELETED
			else -> UNKNOWN
		}
	}
}

/** `MediaRequest.status`: 1 pending approval, 2 approved, 3 declined. */
object SeerrRequestStatus {
	const val PENDING = 1
	const val APPROVED = 2
	const val DECLINED = 3
}

/** TMDB image paths are relative; Seerr serves TMDB data as-is. */
fun seerrImageUrl(path: String?, size: String = "w500"): String? =
	path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }
