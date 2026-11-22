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
	val requests: List<SeerrMediaRequest> = emptyList(),
	val seasons: List<SeerrSeasonStatus> = emptyList(),
)

@Serializable
data class SeerrSeasonStatus(
	val seasonNumber: Int,
	val status: Int? = null,
)

@Serializable
data class SeerrMediaRequest(
	val id: Int,
	val status: Int? = null,
	val is4k: Boolean = false,
	val seasons: List<SeerrRequestSeason> = emptyList(),
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
) {
	val displayTitle get() = title ?: name ?: ""
	val year get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }
	val runtimeMinutes get() = runtime ?: episodeRunTime.firstOrNull()
	val status get() = SeerrMediaStatus.from(mediaInfo?.status)

	/** Real seasons only; TMDB lists specials as season 0. */
	val requestableSeasons get() = seasons.filter { it.seasonNumber > 0 }

	/** Status of a single season, from media info or from any open request covering it. */
	fun seasonStatus(seasonNumber: Int): SeerrMediaStatus {
		mediaInfo?.seasons?.firstOrNull { it.seasonNumber == seasonNumber }?.status?.let {
			val status = SeerrMediaStatus.from(it)
			if (status != SeerrMediaStatus.UNKNOWN) return status
		}
		val requested = mediaInfo?.requests.orEmpty()
			.filter { !it.is4k && it.status != SeerrRequestStatus.DECLINED }
			.any { request -> request.seasons.any { it.seasonNumber == seasonNumber } }
		return if (requested) SeerrMediaStatus.PENDING else SeerrMediaStatus.UNKNOWN
	}
}

@Serializable
data class SeerrRequestBody(
	val mediaType: String,
	val mediaId: Int,
	val seasons: List<Int>? = null,
	val is4k: Boolean = false,
)

@Serializable
data class SeerrError(
	val message: String? = null,
	val error: String? = null,
)

const val MEDIA_MOVIE = "movie"
const val MEDIA_TV = "tv"
const val MEDIA_PERSON = "person"

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
