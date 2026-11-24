package org.jellyfin.androidtv.ui.browsing

import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.home.PinToHome
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest

class CollectionFragment : EnhancedBrowseFragment() {
	private val pinAdapter = ArrayObjectAdapter(GridButtonPresenter())

	override fun setupQueries(rowLoader: RowLoader) {
		// prevent duplicates
		mRows.clear()
		val movies = GetItemsRequest(
			fields = ItemRepository.itemFields,
			parentId = mFolder.id,
			includeItemTypes = setOf(BaseItemKind.MOVIE),
		)
		mRows.add(BrowseRowDef(getString(R.string.lbl_movies), movies, 100))

		val series = GetItemsRequest(
			fields = ItemRepository.itemFields,
			parentId = mFolder.id,
			includeItemTypes = setOf(BaseItemKind.SERIES),
		)
		mRows.add(BrowseRowDef(getString(R.string.lbl_tv_series), series, 100))

		val others = GetItemsRequest(
			fields = ItemRepository.itemFields,
			parentId = mFolder.id,
			excludeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
		)
		mRows.add(BrowseRowDef(getString(R.string.lbl_other), others, 100))

		rowLoader.loadRows(mRows)
	}

	// Erg: a "Pin to home" button under the collection's rows
	override fun addAdditionalRows(rowAdapter: MutableObjectAdapter<Row>) {
		if (!PinToHome.canPin(mFolder)) return
		pinAdapter.clear()
		pinAdapter.add(pinButton())
		rowAdapter.add(ListRow(HeaderItem(rowAdapter.size().toLong(), ""), pinAdapter))
	}

	override fun setupEventListeners() {
		super.setupEventListeners()
		mClickedListener.registerListener(OnItemViewClickedListener { _, item, _, _ ->
			if (item is GridButton && item.id == PIN_BUTTON) {
				PinToHome.toggle(requireContext(), mFolder)
				pinAdapter.replace(0, pinButton())
			}
		})
	}

	private fun pinButton() = GridButton(PIN_BUTTON, PinToHome.label(requireContext(), mFolder))

	private companion object {
		const val PIN_BUTTON = 0x5e7
	}
}
