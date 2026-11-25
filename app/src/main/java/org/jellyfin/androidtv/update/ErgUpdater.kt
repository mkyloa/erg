package org.jellyfin.androidtv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.BuildConfig
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub releases for a newer Erg and installs it.
 *
 * Only release builds update themselves (debug builds are a different app id). Installation goes
 * through Android's package installer, which shows its own confirmation and refuses APKs that
 * aren't signed with Erg's key.
 */
class ErgUpdater(private val context: Context) {
	@Serializable
	data class Release(
		@SerialName("tag_name") val tagName: String,
		val name: String? = null,
		val body: String? = null,
		val draft: Boolean = false,
		val prerelease: Boolean = false,
		val assets: List<Asset> = emptyList(),
	) {
		val version get() = tagName.removePrefix("v")
		val apk get() = assets.firstOrNull { it.name.endsWith(".apk") }
	}

	@Serializable
	data class Asset(
		val name: String,
		@SerialName("browser_download_url") val url: String,
		val size: Long = 0,
		/** "sha256:<hex>", which GitHub computes for every release asset. */
		val digest: String? = null,
	)

	sealed interface State {
		data object Idle : State
		data object Checking : State
		data object UpToDate : State
		data class Available(val release: Release) : State
		data class Downloading(val release: Release, val progress: Float) : State
		/** The user must allow Erg to install apps; the system settings screen was opened. */
		data class NeedsPermission(val release: Release) : State
		data class Installing(val release: Release) : State
		data class Error(val message: String) : State
	}

	private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
	private val json = Json { ignoreUnknownKeys = true }
	private val http = OkHttpClient.Builder()
		.connectTimeout(15, TimeUnit.SECONDS)
		.readTimeout(60, TimeUnit.SECONDS)
		.build()

	/** Downloads outlive the screen that started them, so they run here rather than in its scope. */
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var installJob: Job? = null

	private val _state = MutableStateFlow<State>(State.Idle)
	val state: StateFlow<State> = _state.asStateFlow()

	/** Self-updating only makes sense for signed release builds installed from our releases. */
	val isSupported get() = !BuildConfig.DEBUG && BuildConfig.UPDATE_REPOSITORY.isNotBlank()

	var autoCheck: Boolean
		get() = prefs.getBoolean(KEY_AUTO, true)
		set(value) = prefs.edit { putBoolean(KEY_AUTO, value) }

	val currentVersion: String get() = BuildConfig.VERSION_NAME

	/** Called at startup: checks at most every [CHECK_INTERVAL_MS], unless [force]. */
	suspend fun check(force: Boolean = false) {
		if (!isSupported) return
		if (!force && !autoCheck) return
		val now = System.currentTimeMillis()
		if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return

		_state.value = State.Checking
		_state.value = try {
			val release = fetchLatest()
			prefs.edit { putLong(KEY_LAST_CHECK, now) }
			when {
				release == null || release.apk == null -> State.UpToDate
				!isNewer(release.version, currentVersion) -> State.UpToDate
				// Automatic checks stay quiet about a version the user already said no to
				!force && prefs.getString(KEY_DISMISSED, null) == release.version -> State.Idle
				else -> State.Available(release)
			}
		} catch (err: Exception) {
			Timber.w(err, "Update check failed")
			State.Error(err.message ?: err.toString())
		}
	}

	/** "Later": hide the prompt; the next automatic check (at most every 12 hours) offers it again. */
	fun dismiss() {
		_state.value = State.Idle
	}

	/** "Skip this version": automatic checks stay quiet until a newer release comes out. */
	fun skip() {
		val current = _state.value
		if (current is State.Available) prefs.edit { putString(KEY_DISMISSED, current.release.version) }
		_state.value = State.Idle
	}

	private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "Erg/${BuildConfig.VERSION_NAME}")
			.build()
		http.newCall(request).execute().use { response ->
			if (response.code == 404) return@withContext null // no releases yet
			if (!response.isSuccessful) throw IllegalStateException("GitHub returned HTTP ${response.code}")
			json.decodeFromString<Release>(response.body!!.string()).takeUnless { it.draft }
		}
	}

	/** Downloads the release APK and hands it to the system installer. Ignored while one is running. */
	fun install(release: Release) {
		if (installJob?.isActive == true) return
		installJob = scope.launch { runInstall(release) }
	}

	private suspend fun runInstall(release: Release) {
		val asset = release.apk ?: return
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
			_state.value = State.NeedsPermission(release)
			context.startActivity(
				Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			)
			return
		}

		try {
			val file = download(release, asset)
			_state.value = State.Installing(release)
			withContext(Dispatchers.IO) { commitSession(file) }
		} catch (err: CancellationException) {
			throw err
		} catch (err: Exception) {
			Timber.e(err, "Update install failed")
			_state.value = State.Error(err.message ?: err.toString())
		}
	}

	/** The installer's verdict, from [UpdateInstallReceiver]; without it the state stays "Installing". */
	internal fun onInstallResult(status: Int, message: String?) {
		val release = (_state.value as? State.Installing)?.release
		_state.value = when (status) {
			PackageInstaller.STATUS_SUCCESS -> State.Idle
			// The user backed out of the system confirmation: offer the update again
			PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { State.Available(it) } ?: State.Idle
			else -> State.Error(message ?: "installer status $status")
		}
	}

	private suspend fun download(release: Release, asset: Asset): File = withContext(Dispatchers.IO) {
		val dir = File(context.cacheDir, "updates").apply { mkdirs() }
		dir.listFiles()?.forEach { it.delete() }
		val target = File(dir, asset.name)
		val request = Request.Builder().url(asset.url).header("User-Agent", "Erg/${BuildConfig.VERSION_NAME}").build()
		http.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IllegalStateException("Download failed: HTTP ${response.code}")
			val body = response.body!!
			val total = body.contentLength().takeIf { it > 0 } ?: asset.size
			body.byteStream().use { input ->
				target.outputStream().use { output ->
					val buffer = ByteArray(64 * 1024)
					var done = 0L
					var lastReported = 0f
					while (true) {
						ensureActive()
						val read = input.read(buffer)
						if (read < 0) break
						output.write(buffer, 0, read)
						done += read
						if (total > 0) {
							val progress = (done.toFloat() / total).coerceIn(0f, 1f)
							if (progress - lastReported >= 0.01f) {
								lastReported = progress
								_state.value = State.Downloading(release, progress)
							}
						}
					}
				}
			}
		}
		verify(target, asset)
		target
	}

	/** Catches truncated or corrupted downloads before the installer gives an opaque error. */
	private fun verify(file: File, asset: Asset) {
		if (asset.size > 0 && file.length() != asset.size) {
			throw IllegalStateException("Download incomplete: ${file.length()} of ${asset.size} bytes")
		}
		val expected = asset.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:") ?: return
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				digest.update(buffer, 0, read)
			}
		}
		val actual = digest.digest().joinToString("") { "%02x".format(it) }
		if (!actual.equals(expected, ignoreCase = true)) throw IllegalStateException("Download is corrupted (checksum mismatch)")
	}

	private fun commitSession(apk: File) {
		val installer = context.packageManager.packageInstaller
		val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
			setAppPackageName(context.packageName)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
		}
		val sessionId = installer.createSession(params)
		installer.openSession(sessionId).use { session ->
			session.openWrite("erg.apk", 0, apk.length()).use { out ->
				apk.inputStream().use { it.copyTo(out) }
				session.fsync(out)
			}
			val flags = PendingIntent.FLAG_UPDATE_CURRENT or
				(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
			val callback = PendingIntent.getBroadcast(
				context, sessionId, Intent(context, UpdateInstallReceiver::class.java), flags,
			)
			session.commit(callback.intentSender)
		}
	}

	companion object {
		private const val KEY_AUTO = "auto_check"
		private const val KEY_LAST_CHECK = "last_check"
		private const val KEY_DISMISSED = "dismissed_version"
		private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

		/**
		 * Release notes as plain text for the TV: drops the "Erg x.y.z" title line, markdown headings
		 * and emphasis, links, and GitHub's "by @user in <url>" / "Full Changelog" lines.
		 */
		fun readableNotes(body: String?): String? = body
			?.lines()
			?.map { it.trim() }
			?.filterNot { it.startsWith("**Full Changelog**") || it.matches(Regex("^Erg v?\\d.*")) || it.startsWith("<!--") }
			?.map { line ->
				line.replace(Regex("^#+\\s*"), "")
					.replace(Regex("^[*-]\\s+"), "• ")
					.replace(Regex("\\s+by @\\S+ in https?://\\S+"), "")
					.replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
					.replace(Regex("\\*\\*|__|`"), "")
			}
			?.joinToString("\n")
			?.replace(Regex("\n{3,}"), "\n\n")
			?.trim()
			?.takeIf { it.isNotEmpty() }

		/** True when [candidate] is a newer semantic version than [current] (pre-releases sort lower). */
		fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0

		internal fun compareVersions(a: String, b: String): Int {
			fun split(v: String): Pair<List<Int>, String?> {
				val core = v.substringBefore('-')
				val pre = v.substringAfter('-', "").ifEmpty { null }
				return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
			}
			val (coreA, preA) = split(a.removePrefix("v"))
			val (coreB, preB) = split(b.removePrefix("v"))
			for (i in 0 until maxOf(coreA.size, coreB.size)) {
				val diff = coreA.getOrElse(i) { 0 } - coreB.getOrElse(i) { 0 }
				if (diff != 0) return diff
			}
			return when {
				preA == preB -> 0
				preA == null -> 1 // 1.0.0 > 1.0.0-beta.1
				preB == null -> -1
				else -> comparePreRelease(preA, preB)
			}
		}

		/** Compares "rc.10" and "rc.9" part by part, numbers as numbers (semver rules). */
		private fun comparePreRelease(a: String, b: String): Int {
			val partsA = a.split('.')
			val partsB = b.split('.')
			for (i in 0 until minOf(partsA.size, partsB.size)) {
				val numA = partsA[i].toIntOrNull()
				val numB = partsB[i].toIntOrNull()
				val diff = when {
					numA != null && numB != null -> numA.compareTo(numB)
					numA != null -> -1 // numeric parts sort before text
					numB != null -> 1
					else -> partsA[i].compareTo(partsB[i])
				}
				if (diff != 0) return diff
			}
			return partsA.size.compareTo(partsB.size)
		}
	}
}

/** Receives the installer's result; shows the system confirmation when it asks for one. */
class UpdateInstallReceiver : BroadcastReceiver(), KoinComponent {
	private val updater: ErgUpdater by inject()

	override fun onReceive(context: Context, intent: Intent) {
		when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				@Suppress("DEPRECATION")
				val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
				} else intent.getParcelableExtra(Intent.EXTRA_INTENT)
				confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
			}
			else -> {
				val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
				if (status == PackageInstaller.STATUS_SUCCESS) Timber.i("Erg update installed")
				else Timber.w("Erg update failed: status %d %s", status, message)
				updater.onInstallResult(status, message)
			}
		}
	}
}
