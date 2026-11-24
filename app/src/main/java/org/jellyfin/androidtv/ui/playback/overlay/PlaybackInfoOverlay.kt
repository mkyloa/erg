package org.jellyfin.androidtv.ui.playback.overlay

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.sdk.model.api.PlayMethod
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * A small panel in the top-left corner with what is playing and how: direct play or transcode
 * (and why), codecs, decoder, bitrate, buffer and dropped frames. Toggled from the player controls,
 * refreshed every second, and kept on across episodes until turned off.
 */
object PlaybackInfoOverlay {
	private const val REFRESH_MS = 1000L
	private var panel: WeakReference<TextView>? = null

	val isShown get() = panel?.get()?.isAttachedToWindow == true

	fun toggle(controller: PlaybackController) {
		if (isShown) hide() else show(controller)
	}

	private fun hide() {
		panel?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
		panel = null
	}

	private fun show(controller: PlaybackController) {
		val root = controller.fragment?.view as? ViewGroup ?: return
		val context = root.context
		val density = context.resources.displayMetrics.density
		val view = TextView(context).apply {
			typeface = Typeface.MONOSPACE
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			setTextColor(org.jellyfin.androidtv.util.Utils.getThemeColor(context, org.jellyfin.androidtv.R.attr.textColorPrimary))
			setLineSpacing(0f, 1.15f)
			gravity = Gravity.START
			val pad = (12 * density).toInt()
			setPadding(pad, pad, pad, pad)
			background = GradientDrawable().apply {
				setColor(Color.argb(0xE6, 0, 0, 0))
				cornerRadius = 8 * density
			}
			elevation = 16 * density
			isFocusable = false
		}
		val params = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			val margin = (24 * density).toInt()
			setMargins(margin, margin, margin, margin)
		}
		root.addView(view, params)
		panel = WeakReference(view)

		val refresh = object : Runnable {
			override fun run() {
				if (panel?.get() !== view) return
				view.text = lines(controller).joinToString("\n")
				view.postDelayed(this, REFRESH_MS)
			}
		}
		refresh.run()
	}

	private fun lines(controller: PlaybackController): List<String> = buildList {
		val stream = controller.currentStreamInfo
		val source = controller.currentMediaSource
		if (stream != null) {
			val method = when (stream.playMethod) {
				PlayMethod.DIRECT_PLAY -> "Direct play"
				PlayMethod.DIRECT_STREAM -> "Direct stream (remux)"
				PlayMethod.TRANSCODE -> "Transcode"
			}
			add("Playback: $method")
			if (stream.playMethod != PlayMethod.DIRECT_PLAY) {
				source?.transcodingUrl
					?.let { runCatching { Uri.parse(it).getQueryParameter("TranscodeReasons") }.getOrNull() }
					?.takeIf { it.isNotBlank() }
					?.let { reasons -> add("Reason: " + reasons.split(',').joinToString(", ") { it.toReadableReason() }) }
			}
		}
		source?.let {
			val file = listOfNotNull(
				it.container,
				it.bitrate?.takeIf { rate -> rate > 0 }?.let { rate -> String.format(Locale.US, "%.1f Mbps", rate / 1_000_000.0) },
			).joinToString(" · ")
			if (file.isNotEmpty()) add("Source: $file")
		}
		addAll(runCatching { controller.engineStats }.getOrNull().orEmpty())
	}

	/** "AudioCodecNotSupported" → "audio codec not supported". */
	private fun String.toReadableReason() = trim()
		.replace(Regex("([a-z])([A-Z])"), "$1 $2")
		.lowercase(Locale.US)
}
