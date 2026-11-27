package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Action
import androidx.leanback.widget.PlaybackControlsRow

/**
 * Erg: gives every player control the theme's icon color. Leanback's own actions (play/pause,
 * rewind, fast forward, skip) come out white, and a few of our icons have white baked in, while the
 * rest follow colorControlNormal. This tints them all with that same color.
 */
object PlayerIconTint {
	@JvmStatic
	fun apply(context: Context, vararg actions: Action?) {
		val color = controlColor(context)
		actions.filterNotNull().forEach { tint(it, color) }
	}

	private fun tint(action: Action, color: Int) = when (action) {
		is PlaybackControlsRow.MultiAction -> tintMultiAction(action, color)
		else -> action.icon = action.icon?.mutate()?.apply { setTint(color) }
	}

	private fun tintMultiAction(action: PlaybackControlsRow.MultiAction, color: Int) {
		val count = action.actionCount
		if (count == 0) return
		val drawables = Array(count) { i ->
			val drawable = action.getDrawable(i)
			// The "recording" state keeps its red dot
			if (action is RecordAction && i == RecordAction.INDEX_RECORDING) drawable
			else drawable?.mutate()?.apply { setTint(color) }
		}
		val index = action.index
		action.setDrawables(drawables)
		// setDrawables leaves the current icon alone; re-select the index so it picks the tinted one
		action.index = index
	}

	private fun controlColor(context: Context): Int {
		val value = TypedValue()
		if (!context.theme.resolveAttribute(androidx.appcompat.R.attr.colorControlNormal, value, true)) return Color.WHITE
		return when {
			value.resourceId != 0 -> ContextCompat.getColorStateList(context, value.resourceId)?.defaultColor ?: Color.WHITE
			value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
			else -> Color.WHITE
		}
	}
}
