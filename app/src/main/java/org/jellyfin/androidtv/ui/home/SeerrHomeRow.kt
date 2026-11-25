package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.unit.dp
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.seerr.SeerrResult
import org.jellyfin.androidtv.seerr.seerrImageUrl
import org.jellyfin.androidtv.seerr.ui.SeerrImage
import org.jellyfin.androidtv.seerr.ui.StatusBadge
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.item.rememberCardShape
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

/** Erg: a home row of Seerr titles (not in the library). Selecting one opens its Seerr page. */
class SeerrHomeRow(
	private val title: String,
	private val items: List<SeerrResult>,
) : HomeFragmentRow {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val adapter = ArrayObjectAdapter(SeerrCardPresenter())
		items.forEach(adapter::add)
		rowsAdapter.add(ListRow(HeaderItem(title), adapter))
	}
}

/** Poster card for a [SeerrResult], sized like the library's poster cards. */
class SeerrCardPresenter : Presenter() {
	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val item = MutableStateFlow<SeerrResult?>(null)
		val focused = MutableStateFlow(false)
		val view = ComposeView(parent.context).apply {
			// Leanback measures cards before they're attached; borrow the row's composition, like CardPresenter
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
			isFocusableInTouchMode = true
			setOnFocusChangeListener { _, hasFocus -> focused.value = hasFocus }
			setContent {
				JellyfinTheme {
					val current by item.collectAsState()
					val isFocused by focused.collectAsState()
					current?.let { SeerrPoster(it, isFocused) }
				}
			}
		}
		return SeerrViewHolder(view, item)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		(viewHolder as SeerrViewHolder).item.value = item as? SeerrResult
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		(viewHolder as SeerrViewHolder).item.value = null
	}

	private class SeerrViewHolder(view: ComposeView, val item: MutableStateFlow<SeerrResult?>) : ViewHolder(view)
}

private val PosterWidth = 113.dp
private val PosterHeight = 170.dp

@androidx.compose.runtime.Composable
private fun SeerrPoster(item: SeerrResult, focused: Boolean) {
	val colors = JellyfinTheme.colorScheme
	val shape = rememberCardShape()
	// Like the library's poster cards: no caption, the poster says it all
	Box(
		Modifier
			.size(PosterWidth, PosterHeight)
			.clip(shape)
			.background(colors.onBackground.copy(alpha = 0.08f), shape)
			.then(if (focused) Modifier.border(2.dp, colors.onBackground, shape) else Modifier)
	) {
		val poster = seerrImageUrl(item.posterPath, "w342")
		if (poster != null) SeerrImage(url = poster, contentDescription = item.displayTitle, shape = shape, modifier = Modifier.fillMaxSize())
		else Text(item.displayTitle, color = colors.onBackground, modifier = Modifier.align(Alignment.Center).padding(8.dp))
		StatusBadge(item.status, Modifier.align(Alignment.TopEnd).padding(6.dp))
	}
}
