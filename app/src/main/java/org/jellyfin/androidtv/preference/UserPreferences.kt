package org.jellyfin.androidtv.preference

import android.content.Context
import androidx.preference.PreferenceManager
import org.jellyfin.androidtv.preference.UserPreferences.Companion.screensaverInAppEnabled
import org.jellyfin.androidtv.preference.constant.AVCLevel
import org.jellyfin.androidtv.preference.constant.AppLanguage
import org.jellyfin.androidtv.preference.constant.AppTheme
import org.jellyfin.androidtv.preference.constant.AudioBehavior
import org.jellyfin.androidtv.preference.constant.AudioLanguage
import org.jellyfin.androidtv.preference.constant.BackdropBehavior
import org.jellyfin.androidtv.preference.constant.BitstreamAudioMode
import org.jellyfin.androidtv.preference.constant.BufferLength
import org.jellyfin.androidtv.preference.constant.CarouselSortBy
import org.jellyfin.androidtv.preference.constant.ClockBehavior
import org.jellyfin.androidtv.preference.constant.HEVCLevel
import org.jellyfin.androidtv.preference.constant.HdrOverrideMode
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.preference.constant.RefreshRateSwitchingBehavior
import org.jellyfin.androidtv.preference.constant.ScreensaverSortBy
import org.jellyfin.androidtv.preference.constant.SkipDuration
import org.jellyfin.androidtv.preference.constant.StillWatchingBehavior
import org.jellyfin.androidtv.preference.constant.SubtitleLanguage
import org.jellyfin.androidtv.preference.constant.VideoPlayerEngine
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.androidtv.ui.playback.segment.toMediaSegmentActionsString
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.floatPreference
import org.jellyfin.preference.intPreference
import org.jellyfin.preference.longPreference
import org.jellyfin.preference.store.SharedPreferenceStore
import org.jellyfin.preference.stringPreference
import org.jellyfin.sdk.model.api.MediaSegmentType
import kotlin.time.Duration.Companion.minutes

/**
 * User preferences are configurable by the user and change behavior of the application.
 * When changing preferences migration should be added to the init function.
 *
 * @param context Context to get the SharedPreferences from
 */
class UserPreferences(context: Context) : SharedPreferenceStore(
	sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
) {

	companion object {
		/**
		 * App language preference
		 * Uses the device default if not set
		 */
		var appLanguage = enumPreference("app_language", AppLanguage.SYSTEM_DEFAULT)

		/* Display */
		/**
		 * Image quality preference: low, normal, high
		 */
		var imageQuality = stringPreference("image_quality", "normal")
		/**
		 * Select the app theme
		 */
		var appTheme = enumPreference("app_theme", AppTheme.MUTED_PURPLE)

		/**
		 * Behavior of app background while browsing
		 */
		var backdropBehavior = enumPreference("backdrop_behavior", BackdropBehavior.BACKDROP_WITH_BLUR)
		var backdropEnabled = booleanPreference("pref_show_backdrop", true)

		/**
		 * Backdrop fading intensity from 0 (no fade) to 1.0 (full fade)
		 */
		var backdropFadingIntensity = floatPreference("pref_backdrop_fading_intensity", 0.6f)

		/**
		 * Use dynamic extracted colors for backdrop instead of solid theme color
		 */
		var backdropDynamicColors = booleanPreference("pref_backdrop_dynamic_colors", true)

		/**
		 * Show premieres on home screen
		 */
		var premieresEnabled = booleanPreference("pref_enable_premieres", false)

		/**
		 * Enable management of media like deleting items when the user has sufficient permissions.
		 */
		var mediaManagementEnabled = booleanPreference("enable_media_management", false)

		/**
		 * Enable Christmas snowfall effect on carousel
		 */
		var snowfallEnabled = booleanPreference("pref_snowfall_enabled", false)

		/* Playback - General*/
		/**
		 * Maximum bitrate in megabit for playback.
		 */
		var maxBitrate = stringPreference("pref_max_bitrate", "200")

		/**
		 * Auto-play next item
		 */
		var mediaQueuingEnabled = booleanPreference("pref_enable_tv_queuing", true)

		/**
		 * Enable the next up screen or not
		 */
		var nextUpBehavior = enumPreference("next_up_behavior", NextUpBehavior.EXTENDED)

		/**
		 * Next up timeout before playing next item
		 * Stored in milliseconds
		 */
		var nextUpTimeout = intPreference("next_up_timeout", 1000 * 7)

		/**
		 * Duration in milliseconds before player UI controls automatically hide
		 * Default: 15000ms (15 seconds)
		 */
		var playerControlsHideDuration = intPreference("pref_player_controls_hide_duration", 15_000)
		/**
		 * Duration in seconds to subtract from resume time
		 */
		var resumeSubtractDuration = stringPreference("pref_resume_preroll", "0")

		/**
		 * Enable cinema mode
		 */
		var cinemaModeEnabled = booleanPreference("pref_enable_cinema_mode", true)

		/* Playback - Video */
		/**
		 * Whether to use an external playback application or not.
		 */
		var useExternalPlayer = booleanPreference("external_player", false)

		/**
		 * Component name for the external playback application.
		 */
		var externalPlayerComponentName = stringPreference("external_player_component", "")

		/**
		 * Change refresh rate to match media when device supports it
		 */
		var refreshRateSwitchingBehavior = enumPreference("refresh_rate_switching_behavior", RefreshRateSwitchingBehavior.DISABLED)

		/**
		 * Whether ExoPlayer should prefer FFmpeg renderers to core ones.
		 */
		var preferExoPlayerFfmpeg = booleanPreference("exoplayer_prefer_ffmpeg", defaultValue = false)

		/**
		 * User defined AVC level override. AUTO uses device-reported capabilities.
		 */
		var userAVCLevel = enumPreference("user_avc_level", AVCLevel.AUTO)

		/**
		 * User defined HEVC level override. AUTO uses device-reported capabilities.
		 */
		var userHEVCLevel = enumPreference("user_hevc_level", HEVCLevel.AUTO)

		/**
		 * Playback buffer size preset.
		 */
		var bufferLength = enumPreference("buffer_length", BufferLength.AUTO)

		/**
		 * Enable hardware acceleration for video decoding.
		 */
		var hardwareAccelerationEnabled = booleanPreference("hardware_acceleration_enabled", defaultValue = true)


		/* Playback - Audio related */
		/**
		 * Preferred behavior for audio streaming.
		 */
		var audioBehaviour = enumPreference("audio_behavior", AudioBehavior.DIRECT_STREAM)

		/**
		 * Preferred behavior for audio streaming.
		 */
		var audioNightMode = enumPreference("audio_night_mode", false)

		/**
		 * AC3 bitstream device profile override.
		 */
		var bitstreamAc3 = enumPreference("bitstream_ac3_mode", BitstreamAudioMode.AUTO)

		/**
		 * EAC3 bitstream device profile override.
		 */
		var bitstreamEac3 = enumPreference("bitstream_eac3_mode", BitstreamAudioMode.AUTO)

		/**
		 * DTS bitstream device profile override.
		 */
		var bitstreamDts = enumPreference("bitstream_dts_mode", BitstreamAudioMode.AUTO)

		/**
		 * TrueHD bitstream device profile override.
		 */
		var bitstreamTrueHd = enumPreference("bitstream_truehd_mode", BitstreamAudioMode.AUTO)

		/**
		 * HDR10 device profile override.
		 */
		var hdr10Override = enumPreference("hdr10_override", HdrOverrideMode.AUTO)

		/**
		 * HDR10+ device profile override.
		 */
		var hdr10PlusOverride = enumPreference("hdr10_plus_override", HdrOverrideMode.AUTO)


		/**
		 * Dolby Vision Profile 5 device profile override.
		 */
		var doviProfile5Override = enumPreference("dovi_profile_5_override", HdrOverrideMode.AUTO)

		/**
		 * Dolby Vision Profile 7 device profile override.
		 */
		var doviProfile7Override = enumPreference("dovi_profile_7_override", HdrOverrideMode.AUTO)

		/**
		 * Dolby Vision Profile 8 device profile override.
		 */
		var doviProfile8Override = enumPreference("dovi_profile_8_override", HdrOverrideMode.AUTO)

		/* Live TV */
		/**
		 * Use direct play
		 */
		var liveTvDirectPlayEnabled = booleanPreference("pref_live_direct", true)

		/* Developer options */
		/**
		 * Show additional debug information
		 */
		var debuggingEnabled = booleanPreference("pref_enable_debug", false)


		/**
		 * When to show the clock.
		 */
		var clockBehavior = enumPreference("pref_clock_behavior", ClockBehavior.ALWAYS)

		/**
		 * Set when watched indicators should show on MyImageCardViews
		 */
		var watchedIndicatorBehavior = enumPreference("pref_watched_indicator_behavior", WatchedIndicatorBehavior.ALWAYS)

		/**
		 * Show resolution badge on movie cards
		 */
		var showResolutionBadge = booleanPreference("pref_show_resolution_badge", true)

		/**
		 * Show audio codec badge on movie cards
		 */
		var showAudioCodecBadge = booleanPreference("pref_show_audio_codec_badge", false)

		/**
		 * Enable series thumbnails in home screen rows
		 */
		var seriesThumbnailsEnabled = booleanPreference("pref_enable_series_thumbnails", true)

		/**
		 * Maximum days since last watch to show an item in next up.
		 * Set to 0 to disable (no cutoff).
		 */
		var homeNextUpMaxDays = intPreference("home_next_up_max_days", 0)

		/**
		 * Enable thumbnails in GoogleTV Launcher
		 */
		var launcherThumbnailsEnabled = booleanPreference("pref_enable_launcher_thumbnails", true)

		/**
		 * Enable all Android TV launcher channels
		 */
		var launcherChannelsEnabled = booleanPreference("pref_enable_launcher_channels", true)

		/**
		 * Sorting method for carousel items
		 */
		var carouselSortBy = enumPreference("carousel_sort_by", CarouselSortBy.RELEASE_DATE)

		/**
		 * Enable Series in carousel alongside Movies
		 */
		var carouselIncludeSeries = booleanPreference("carousel_include_series", false)

		/**
		 * Subtitles foreground color
		 */
		var subtitlesBackgroundColor = longPreference("subtitles_background_color", 0x00FFFFFF)

		/**
		 * Subtitles foreground color
		 */
		var subtitlesTextColor = longPreference("subtitles_text_color", 0xFFFFFFFF)

		/**
		 * Subtitles stroke color
		 */
		var subtitleTextStrokeColor = longPreference("subtitles_text_stroke_color", 0xFF000000)

		/**
		 * Subtitles font size (1.0f = 100%)
		 */
		var subtitlesTextSize = floatPreference("subtitles_text_size", 24f)

		/**
		 * Subtitles offset
		 */
		var subtitlesOffsetPosition = floatPreference("subtitles_offset_position", 0.08f)

		/**
		 * Subtitles text weight value (400 = normal, 700 = bold)
		 */
		var subtitlesTextWeightValue = intPreference("subtitles_text_weight_value", 400)

		/**
		 * Default subtitle language
		 * Default is set to use video's default Set Subtitle
		 */
		var defaultSubtitleLanguage = enumPreference("default_subtitle_language", SubtitleLanguage.DEFAULT)

		/**
		 * Default audio language
		 * Default is set to English
		 */
		var defaultAudioLanguage = enumPreference("default_audio_language", AudioLanguage.ENGLISH)

		/**
		 * Skip commentary audio tracks
		 * When enabled, audio tracks marked as commentary will be skipped during selection
		 */
		var skipCommentaryTracks = booleanPreference("skip_commentary_tracks", true)

		/**
		 * Show screensaver in app
		 */
		var screensaverInAppEnabled = booleanPreference("screensaver_inapp_enabled", true)

		/**
		 * Timeout before showing the screensaver in app, depends on [screensaverInAppEnabled].
		 */
		var screensaverInAppTimeout = longPreference("screensaver_inapp_timeout", 5.minutes.inWholeMilliseconds)

		/**
		 * Age rating used to filter items in the screensaver. Use -1 to disable (omits parameter from requests).
		 */
		var screensaverAgeRatingMax = intPreference("screensaver_agerating_max", -1)

		/**
		 * Whether items shown in the screensaver are required to have an age rating set.
		 */
		var screensaverAgeRatingRequired = booleanPreference("screensaver_agerating_required", true)

		/**
		 * Show clock in screensaver
		 */
		var screensaverClockEnabled = booleanPreference("screensaver_clock_enabled", true)

		/**
		 * Show logo in screensaver
		 */
		var screensaverLogoEnabled = booleanPreference("screensaver_logo_enabled", true)

		/**
		 * Sorting method for screensaver content
		 */
		var screensaverSortBy = enumPreference("screensaver_sort_by", ScreensaverSortBy.RANDOM)

		/**
		 * Delay when starting video playback after loading the video player.
		 */
		var videoStartDelay = longPreference("video_start_delay", 0)

		/**
		 * The actions to take for each media segment type. Managed by the [org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository].
		 */
		var mediaSegmentActions = stringPreference(
			key = "media_segment_actions",
			defaultValue = mapOf(
				MediaSegmentType.INTRO to MediaSegmentAction.ASK_TO_SKIP,
				MediaSegmentType.OUTRO to MediaSegmentAction.ASK_TO_SKIP,
			).toMediaSegmentActionsString()
		)

		/**
		 * Duration for the skip button visibility timer.
		 */
		var skipDuration = enumPreference("skip_duration", SkipDuration.DEFAULT_8_SECONDS)

		/**
		 * Preferred behavior for player aspect ratio (zoom mode).
		 */
		var playerZoomMode = enumPreference("player_zoom_mode", ZoomMode.FIT)

		/**
		 * Enable libass.
		 */
		var assDirectPlay = booleanPreference("libass_enabled", false)

		/**
		 * Always burn in subtitles when transcoding.
		 */
		var subtitlesBurnDuringTranscode = booleanPreference("subtitles_burn_during_transcode", false)

		/**
		 * Enable PGS subtitle direct-play.
		 */
		var pgsDirectPlay = booleanPreference("pgs_enabled", true)

		/**
		 * Enable the use of software-based codecs.
		 */
		var softwareCodecsEnabled = booleanPreference("software_codecs_enabled", true)

		/**
		 * Stores the interval for the photo player.
		 */
		var photoPlayerPresentationDelay = longPreference("photo_player_presentation_delay", 8000)

		/**
		 * Integrate with androidx.tvprovider for the Android TV home screen.
		 */
		var tvProviderEnabled = booleanPreference("tv_provider_enabled", true)

		/**
		 * Enable preloading of images for better performance
		 */
		var preloadImages = booleanPreference("preload_images", true)

		/**
		 * Disk cache size in MB for images
		 * Default: 250mb
		 */
		var diskCacheSizeMb = intPreference("disk_cache_size_mb", 250)

		/**
		 * Custom Sections item limit for home screen
		 * Default: 10 items
		 */
		var genreItemLimit = intPreference("genre_item_limit", 10)


		/**
		 * Player used for video playback (Erg).
		 */
		var videoPlayerEngine = enumPreference("video_player_engine", VideoPlayerEngine.EXOPLAYER)

		/**
		 * Play Dolby Vision profile 7 as its HDR10 base layer on devices that can't decode it (Erg).
		 */
		var doviProfile7Fallback = booleanPreference("dovi_profile7_fallback", true)

		/**
		 * Enable still watching
		 */
		var stillWatchingBehavior = enumPreference("enable_still_watching", StillWatchingBehavior.DISABLED)

		/**
		 * Use playback rewrite module for video
		 */
		var playbackRewriteVideoEnabled = booleanPreference("playback_new", false)

		/**
		 * Subtitles bold text
		 */
		var subtitlesTextWeight = intPreference("subtitles_text_weight", 400)
	}

	init {
		// Note: Create a single migration per app version
		// Note: Migrations are never executed for fresh installs
		// Note: Old migrations are removed occasionally
		runMigrations {
			// v0.15.z to v0.16.0
			migration(toVersion = 7) {
				// Enable playback rewrite for music
				putBoolean("playback_new_audio", true)
			}

			// v0.17.z to v0.18.0
			migration(toVersion = 8) {
				// Set subtitle background color to black if it was enabled in a previous version
				val subtitlesBackgroundEnabled = it.getBoolean("subtitles_background_enabled", true)
				putLong("subtitles_background_color", if (subtitlesBackgroundEnabled) 0XFF000000L else 0X00FFFFFFL)

				// Set subtitle text stroke color to black if it was enabled in a previous version
				val subtitleStrokeSize = it.getInt("subtitles_stroke_size", 0)
				putLong("subtitles_text_stroke_color", if (subtitleStrokeSize > 0) 0XFF000000L else 0X00FFFFFFL)
			}

			// v0.19.0 to v0.20.0
			migration(toVersion = 9) {
				// Reset subtitle text size as we changed from fractional sizing to absolute sizing
				remove("subtitles_text_size")

				// Set the BackdropBehavior if it was enabled in a previous version
				val backdropEnabled = it.getBoolean("pref_show_backdrop", true)
				putString(
					"backdrop_behavior",
					if (backdropEnabled) BackdropBehavior.BACKDROP_WITH_BLUR.name else BackdropBehavior.DISABLED.name
				)
			}
		}

	}
}
