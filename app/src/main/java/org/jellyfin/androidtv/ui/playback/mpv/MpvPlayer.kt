package org.jellyfin.androidtv.ui.playback.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import dev.jdtech.mpv.MPVLib
import org.jellyfin.androidtv.preference.constant.ZoomMode
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Video playback through libmpv, used as an alternative to ExoPlayer.
 *
 * mpv can direct-play nearly any container, codec and subtitle format (including full ASS
 * styling), so far fewer files need transcoding on the server. Track numbers are translated
 * between Jellyfin's media stream indexes and mpv's track ids.
 *
 * All callbacks to [Listener] arrive on the main thread.
 */
class MpvPlayer(
	private val context: Context,
	private val listener: Listener,
	private val options: Options,
) : MPVLib.EventObserver, SurfaceHolder.Callback {
	interface Listener {
		fun onPrepared()
		fun onPlayingChanged(isPlaying: Boolean)
		fun onCompletion()
		fun onError(message: String)
		fun onSpeedChanged(speed: Float)
	}

	data class Options(
		/** Value for the HTTP Authorization header sent with every request. */
		val authorizationHeader: String?,
		val userAgent: String,
		/** Codecs to pass through to the receiver, e.g. "ac3,eac3,dts,dts-hd,truehd". Empty for none. */
		val audioSpdif: String,
		/** Demuxer cache size in MiB. */
		val cacheMegabytes: Int = 96,
		val subtitleScale: Double = 1.0,
		val debugLogging: Boolean = false,
	)

	/** A subtitle file the server delivers separately from the video. */
	data class ExternalSubtitle(val jellyfinIndex: Int, val url: String, val title: String?, val language: String?)

	private class PositionCallback(val positionMs: Long, val callback: Runnable)

	private val mainHandler = Handler(Looper.getMainLooper())
	private val mpv: MPVLib = MPVLib.create(context) ?: throw IllegalStateException("libmpv failed to load")

	val surfaceView = SurfaceView(context).apply { holder.addCallback(this@MpvPlayer) }

	private var surfaceReady = false
	private var pendingLoad: (() -> Unit)? = null
	private var fileLoaded = false
	private var released = false

	private var paused = true
	private var pausedForCache = false
	private var positionMs = 0L
	private var durationMs = 0L
	private var cacheEndMs = 0L
	private var speed = 1.0f
	private var lastPlayingState = false

	private var externalSubtitles: List<ExternalSubtitle> = emptyList()
	private var initialAudioIndex: Int? = null
	private var initialSubtitleIndex: Int? = null
	private val positionCallbacks = mutableListOf<PositionCallback>()

	init {
		configure()
		mpv.init()
		// Options that are applied after init
		mpv.setOptionString("save-position-on-quit", "no")
		mpv.addObserver(this)
		observe("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
		observe("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
		observe("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
		observe("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
		observe("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
		observe("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
	}

	private fun observe(name: String, format: Int) = mpv.observeProperty(name, format)

	private fun configure() = with(mpv) {
		// No config files or key bindings: the app drives everything
		setOptionString("config", "no")
		setOptionString("input-default-bindings", "no")
		setOptionString("input-vo-keyboard", "no")
		setOptionString("idle", "yes")
		setOptionString("keep-open", "no")
		setOptionString("terminal", if (options.debugLogging) "yes" else "no")
		setOptionString("msg-level", if (options.debugLogging) "all=v" else "all=warn")

		// Video: render through OpenGL ES on the attached surface, decode in hardware when possible.
		// The video output starts as "null" and switches to "gpu" once a surface exists.
		setOptionString("vo", "null")
		setOptionString("gpu-context", "android")
		setOptionString("opengl-es", "yes")
		setOptionString("hwdec", "mediacodec-copy")
		setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
		setOptionString("force-window", "no")
		setOptionString("video-sync", "audio")

		// Audio
		setOptionString("ao", "audiotrack,opensles")
		setOptionString("audio-spdif", options.audioSpdif)

		// Network and cache
		setOptionString("cache", "yes")
		setOptionString("demuxer-max-bytes", "${options.cacheMegabytes}MiB")
		setOptionString("demuxer-max-back-bytes", "${options.cacheMegabytes / 3}MiB")
		setOptionString("network-timeout", "30")
		setOptionString("user-agent", options.userAgent)
		options.authorizationHeader?.let { setOptionString("http-header-fields", "Authorization: $it") }

		// libmpv for Android ships no CA bundle; hand it the device's trusted certificates so
		// HTTPS servers are verified instead of disabling verification.
		exportSystemCertificates()?.let {
			setOptionString("tls-ca-file", it.absolutePath)
			setOptionString("tls-verify", "yes")
		}

		// Subtitles: keep embedded ASS styling, scale plain text subtitles
		setOptionString("sub-scale", String.format(Locale.US, "%.2f", options.subtitleScale))
		setOptionString("sub-auto", "no")
		setOptionString("slang", "")
		setOptionString("sid", "no")
	}

	private fun exportSystemCertificates(): File? = runCatching {
		val file = File(context.cacheDir, "mpv-ca-bundle.pem")
		// Refresh at most daily; the system store rarely changes
		if (file.exists() && System.currentTimeMillis() - file.lastModified() < 24 * 60 * 60 * 1000L) return@runCatching file

		val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
		val pem = buildString {
			for (alias in store.aliases()) {
				val cert = store.getCertificate(alias) as? X509Certificate ?: continue
				append("-----BEGIN CERTIFICATE-----\n")
				append(Base64.encodeToString(cert.encoded, Base64.DEFAULT))
				append("-----END CERTIFICATE-----\n")
			}
		}
		file.writeText(pem)
		file
	}.onFailure { Timber.w(it, "Could not export system certificates for mpv") }.getOrNull()

	// --- Surface --------------------------------------------------------------------------

	override fun surfaceCreated(holder: SurfaceHolder) {
		if (released) return
		mpv.attachSurface(holder.surface)
		mpv.setOptionString("force-window", "yes")
		mpv.setPropertyString("vo", "gpu")
		surfaceReady = true
		pendingLoad?.invoke()
		pendingLoad = null
	}

	override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
		if (released) return
		mpv.setPropertyString("android-surface-size", "${width}x$height")
	}

	override fun surfaceDestroyed(holder: SurfaceHolder) {
		if (released) return
		surfaceReady = false
		mpv.setPropertyString("vo", "null")
		mpv.setOptionString("force-window", "no")
		mpv.detachSurface()
	}

	/** Places the video surface where the ExoPlayer view would be. */
	fun attachTo(parent: ViewGroup, index: Int, layoutParams: ViewGroup.LayoutParams) {
		parent.addView(surfaceView, index, layoutParams)
	}

	// --- Loading --------------------------------------------------------------------------

	/**
	 * Starts playing [url] at [startMs]. [audioIndex] and [subtitleIndex] are Jellyfin media
	 * stream indexes to select once the file is open (-1 disables subtitles, null keeps mpv's choice).
	 */
	fun load(url: String, startMs: Long, subtitles: List<ExternalSubtitle>, audioIndex: Int?, subtitleIndex: Int?) {
		val load = {
			fileLoaded = false
			externalSubtitles = subtitles
			initialAudioIndex = audioIndex
			initialSubtitleIndex = subtitleIndex
			positionMs = startMs
			mpv.setPropertyString("start", if (startMs > 0) String.format(Locale.US, "%.3f", startMs / 1000.0) else "none")
			mpv.setPropertyBoolean("pause", false)
			mpv.command(arrayOf("loadfile", url, "replace"))
		}
		if (surfaceReady) load() else pendingLoad = load
	}

	private fun onFileLoaded() {
		fileLoaded = true
		mpv.setPropertyString("start", "none")

		// Add external subtitles without selecting them ("auto" would pick one)
		for (subtitle in externalSubtitles) {
			val args = mutableListOf("sub-add", subtitle.url, "auto")
			args += subtitle.title ?: subtitle.language ?: "External"
			subtitle.language?.let { args += it }
			mpv.command(args.toTypedArray())
		}

		initialAudioIndex?.let { selectAudio(it) }
		when (val sub = initialSubtitleIndex) {
			null -> Unit
			-1 -> mpv.setPropertyString("sid", "no")
			else -> selectSubtitle(sub)
		}

		listener.onPrepared()
		updatePlayingState()
	}

	// --- Transport ------------------------------------------------------------------------

	val isPlaying get() = fileLoaded && !paused && !pausedForCache
	val currentPositionMs get() = positionMs
	val currentDurationMs get() = durationMs
	val bufferedPositionMs get() = cacheEndMs
	val playbackSpeed get() = speed
	val isLoaded get() = fileLoaded

	fun play() = mpv.setPropertyBoolean("pause", false)
	fun pause() = mpv.setPropertyBoolean("pause", true)

	fun stop() {
		fileLoaded = false
		pendingLoad = null
		if (!released) mpv.command(arrayOf("stop"))
	}

	fun seekTo(ms: Long) {
		positionMs = ms
		mpv.command(arrayOf("seek", String.format(Locale.US, "%.3f", ms / 1000.0), "absolute"))
	}

	fun setSpeed(value: Float) = mpv.setPropertyDouble("speed", value.toDouble())

	fun setZoom(mode: ZoomMode) {
		when (mode) {
			ZoomMode.FIT -> {
				mpv.setPropertyString("keepaspect", "yes")
				mpv.setPropertyDouble("panscan", 0.0)
			}

			ZoomMode.AUTO_CROP -> {
				mpv.setPropertyString("keepaspect", "yes")
				mpv.setPropertyDouble("panscan", 1.0)
			}

			ZoomMode.STRETCH -> {
				mpv.setPropertyString("keepaspect", "no")
				mpv.setPropertyDouble("panscan", 0.0)
			}
		}
	}

	/** Runs [callback] whenever normal playback passes [positionMs]. */
	fun addPositionCallback(positionMs: Long, callback: Runnable) {
		positionCallbacks += PositionCallback(positionMs, callback)
	}

	fun clearPositionCallbacks() = positionCallbacks.clear()

	/** Decoder and output details for the playback info overlay. */
	fun stats(): List<String> = buildList {
		fun str(name: String) = runCatching { mpv.getPropertyString(name) }.getOrNull()?.takeIf { it.isNotBlank() }
		fun int(name: String) = runCatching { mpv.getPropertyInt(name) }.getOrNull()
		fun dbl(name: String) = runCatching { mpv.getPropertyDouble(name) }.getOrNull()

		val width = int("video-params/w")
		val height = int("video-params/h")
		val video = listOfNotNull(
			str("video-codec"),
			if (width != null && height != null) "${width}x$height" else null,
			dbl("container-fps")?.let { String.format(Locale.US, "%.3f fps", it) },
		).joinToString(" · ")
		if (video.isNotEmpty()) add("Video: $video")
		val color = listOfNotNull(str("video-params/pixelformat"), str("video-params/gamma"), str("video-params/primaries")).joinToString(" · ")
		if (color.isNotEmpty()) add("Color: $color")
		add("Decoder: " + (str("hwdec-current")?.takeIf { it != "no" }?.let { "hardware ($it)" } ?: "software"))
		val audio = listOfNotNull(
			str("audio-codec-name"),
			int("audio-params/channel-count")?.let { org.jellyfin.androidtv.ui.playback.overlay.PlaybackInfoFormat.channels(it) },
			int("audio-params/samplerate")?.let { "${it / 1000} kHz" },
			if (str("audio-spdif")?.contains(str("audio-codec-name") ?: "-") == true) "passthrough" else null,
		).joinToString(" · ")
		if (audio.isNotEmpty()) add("Audio: $audio")
		val bitrate = (int("video-bitrate") ?: 0) + (int("audio-bitrate") ?: 0)
		if (bitrate > 0) add(String.format(Locale.US, "Bitrate: %.1f Mbps", bitrate / 1_000_000.0))
		dbl("demuxer-cache-duration")?.let { add(String.format(Locale.US, "Buffer: %.0f s", it)) }
		add("Dropped frames: decoder ${int("decoder-frame-drop-count") ?: 0}, output ${int("frame-drop-count") ?: 0}")
	}

	// --- Tracks ---------------------------------------------------------------------------

	private data class Track(val id: Int, val type: String, val ffIndex: Int?, val external: Boolean, val externalFile: String?, val selected: Boolean)

	private fun tracks(): List<Track> {
		val count = mpv.getPropertyInt("track-list/count") ?: return emptyList()
		return (0 until count).mapNotNull { i ->
			val id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
			Track(
				id = id,
				type = mpv.getPropertyString("track-list/$i/type").orEmpty(),
				ffIndex = mpv.getPropertyInt("track-list/$i/ff-index"),
				external = mpv.getPropertyBoolean("track-list/$i/external") == true,
				externalFile = mpv.getPropertyString("track-list/$i/external-filename"),
				selected = mpv.getPropertyBoolean("track-list/$i/selected") == true,
			)
		}
	}

	/** Embedded streams keep the container's stream index, which is Jellyfin's media stream index. */
	private fun trackFor(type: String, jellyfinIndex: Int): Track? {
		val all = tracks().filter { it.type == type }
		val external = externalSubtitles.firstOrNull { it.jellyfinIndex == jellyfinIndex }
		return if (external != null) all.firstOrNull { it.external && it.externalFile == external.url }
		else all.firstOrNull { !it.external && it.ffIndex == jellyfinIndex }
	}

	fun selectAudio(jellyfinIndex: Int): Boolean {
		val track = trackFor("audio", jellyfinIndex) ?: return false
		mpv.setPropertyInt("aid", track.id)
		return true
	}

	/** Selects a subtitle by Jellyfin index, or disables subtitles for -1. */
	fun selectSubtitle(jellyfinIndex: Int): Boolean {
		if (jellyfinIndex < 0) {
			mpv.setPropertyString("sid", "no")
			return true
		}
		val track = trackFor("sub", jellyfinIndex) ?: return false
		mpv.setPropertyInt("sid", track.id)
		return true
	}

	/** Jellyfin index of the selected track of [type] ("audio" or "sub"), or -1. */
	fun selectedIndex(type: String): Int {
		val track = tracks().firstOrNull { it.type == type && it.selected } ?: return -1
		if (track.external) return externalSubtitles.firstOrNull { it.url == track.externalFile }?.jellyfinIndex ?: -1
		return track.ffIndex ?: -1
	}

	// --- Events (native thread) -----------------------------------------------------------

	override fun eventProperty(property: String) = Unit
	override fun eventProperty(property: String, value: String) = Unit

	override fun eventProperty(property: String, value: Boolean) = post {
		when (property) {
			"pause" -> paused = value
			"paused-for-cache" -> pausedForCache = value
		}
		updatePlayingState()
	}

	override fun eventProperty(property: String, value: Long) = Unit

	override fun eventProperty(property: String, value: Double) = post {
		when (property) {
			"time-pos" -> onPosition((value * 1000).toLong())
			"duration" -> durationMs = (value * 1000).toLong()
			"demuxer-cache-time" -> cacheEndMs = (value * 1000).toLong()
			"speed" -> {
				speed = value.toFloat()
				listener.onSpeedChanged(speed)
			}
		}
	}

	override fun event(eventId: Int) = post {
		when (eventId) {
			MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> onFileLoaded()
			MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> updatePlayingState()
		}
	}

	override fun eventEndFile(reason: Int, error: Int) = post {
		val wasLoaded = fileLoaded
		fileLoaded = false
		updatePlayingState()
		when (reason) {
			MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_EOF -> if (wasLoaded) listener.onCompletion()
			MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR -> listener.onError("mpv could not play this file (error $error)")
			else -> Unit
		}
	}

	private fun onPosition(newPositionMs: Long) {
		val previous = positionMs
		positionMs = newPositionMs
		// Fire callbacks only on normal forward playback, not when seeking across them
		if (newPositionMs > previous && newPositionMs - previous < 2_000) {
			positionCallbacks
				.filter { it.positionMs in (previous + 1)..newPositionMs }
				.forEach { it.callback.run() }
		}
	}

	private fun updatePlayingState() {
		val playing = isPlaying
		if (playing != lastPlayingState) {
			lastPlayingState = playing
			listener.onPlayingChanged(playing)
		}
	}

	private inline fun post(crossinline block: () -> Unit) {
		mainHandler.post { if (!released) block() }
	}

	// --- Lifecycle ------------------------------------------------------------------------

	fun release() {
		if (released) return
		released = true
		pendingLoad = null
		positionCallbacks.clear()
		mainHandler.removeCallbacksAndMessages(null)
		surfaceView.holder.removeCallback(this)
		runCatching {
			mpv.removeObserver(this)
			mpv.destroy()
		}.onFailure { Timber.w(it, "mpv release failed") }
		(surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
	}
}
