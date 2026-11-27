package org.jellyfin.androidtv.ui.itemdetail.identify

import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.seerr.ui.SeerrTextField
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.itemdetail.FullDetailsFragment
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemLookupApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MovieInfo
import org.jellyfin.sdk.model.api.MovieInfoRemoteSearchQuery
import org.jellyfin.sdk.model.api.RemoteSearchResult
import org.jellyfin.sdk.model.api.SeriesInfo
import org.jellyfin.sdk.model.api.SeriesInfoRemoteSearchQuery
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import org.koin.java.KoinJavaComponent
import timber.log.Timber

/**
 * Erg: Jellyfin's "Identify" (Edit metadata → Identify in the web client). Searches the server's
 * metadata providers for a movie or show, then applies the chosen match, which replaces the item's
 * metadata and optionally its images. Needs an administrator account, like the web client.
 */
object Identify {
	private val supportedTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)

	@JvmStatic
	fun isAvailable(item: BaseItemDto): Boolean {
		if (item.type !in supportedTypes) return false
		val user = KoinJavaComponent.get<UserRepository>(UserRepository::class.java).currentUser.value
		return user?.policy?.isAdministrator == true
	}
}

/** Shows the Identify dialog over the details page, and reopens the page once a match is applied. */
fun FullDetailsFragment.showIdentifyDialog(item: BaseItemDto) {
	val root = requireActivity().findViewById<ViewGroup>(android.R.id.content) ?: return
	val navigationRepository by inject<NavigationRepository>()
	val context = requireContext().applicationContext

	val view = ComposeView(requireContext())
	fun close() = view.post { root.removeView(view) }
	view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
	view.setContent {
		JellyfinTheme {
			IdentifyDialog(
				item = item,
				onDismiss = ::close,
				onApplied = { name ->
					close()
					Toast.makeText(context, context.getString(R.string.erg_identify_done, name), Toast.LENGTH_LONG).show()
					// The server finishes refreshing before it answers, so a fresh page shows the new metadata
					if (isAdded) navigationRepository.navigate(Destinations.itemDetails(item.id), true)
				},
			)
		}
	}
	root.addView(view)
}

private sealed interface Phase {
	data object Form : Phase
	data object Searching : Phase
	data class Results(val results: List<RemoteSearchResult>) : Phase
	data class Failed(val message: String) : Phase
	data class Applying(val result: RemoteSearchResult) : Phase
}

private fun BaseItemDto.providerId(key: String) =
	providerIds?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value.orEmpty()

@Composable
private fun IdentifyDialog(
	item: BaseItemDto,
	onDismiss: () -> Unit,
	onApplied: (name: String) -> Unit,
) {
	val api = koinInject<ApiClient>()
	val scope = rememberCoroutineScope()
	val isSeries = item.type == BaseItemKind.SERIES

	var name by remember { mutableStateOf(item.name.orEmpty()) }
	var year by remember { mutableStateOf(item.productionYear?.toString().orEmpty()) }
	var imdb by remember { mutableStateOf(item.providerId("Imdb")) }
	var tmdb by remember { mutableStateOf(item.providerId("Tmdb")) }
	var tvdb by remember { mutableStateOf(item.providerId("Tvdb")) }
	var replaceImages by remember { mutableStateOf(true) }
	var phase by remember { mutableStateOf<Phase>(Phase.Form) }
	var job by remember { mutableStateOf<Job?>(null) }

	// The details page doesn't always carry provider ids; fill them in from the full item
	LaunchedEffect(item.id) {
		if (imdb.isNotEmpty() || tmdb.isNotEmpty() || tvdb.isNotEmpty()) return@LaunchedEffect
		runCatching { withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId = item.id).content } }
			.onSuccess { full ->
				if (imdb.isEmpty()) imdb = full.providerId("Imdb")
				if (tmdb.isEmpty()) tmdb = full.providerId("Tmdb")
				if (tvdb.isEmpty()) tvdb = full.providerId("Tvdb")
			}
	}

	fun search() {
		job?.cancel()
		phase = Phase.Searching
		job = scope.launch {
			val ids = buildMap {
				imdb.trim().takeIf { it.isNotEmpty() }?.let { put("Imdb", it) }
				tmdb.trim().takeIf { it.isNotEmpty() }?.let { put("Tmdb", it) }
				if (isSeries) tvdb.trim().takeIf { it.isNotEmpty() }?.let { put("Tvdb", it) }
			}
			val searchYear = year.trim().toIntOrNull()
			phase = try {
				val results = withContext(Dispatchers.IO) {
					if (isSeries) {
						api.itemLookupApi.getSeriesRemoteSearchResults(
							SeriesInfoRemoteSearchQuery(
								searchInfo = SeriesInfo(name = name.trim(), year = searchYear, providerIds = ids, isAutomated = false),
								itemId = item.id,
								includeDisabledProviders = true,
							)
						).content
					} else {
						api.itemLookupApi.getMovieRemoteSearchResults(
							MovieInfoRemoteSearchQuery(
								searchInfo = MovieInfo(name = name.trim(), year = searchYear, providerIds = ids, isAutomated = false),
								itemId = item.id,
								includeDisabledProviders = true,
							)
						).content
					}
				}
				Phase.Results(results)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Timber.w(e, "Identify search failed for ${item.id}")
				Phase.Failed(e.message ?: e.javaClass.simpleName)
			}
		}
	}

	fun apply(result: RemoteSearchResult) {
		job?.cancel()
		phase = Phase.Applying(result)
		job = scope.launch {
			try {
				withContext(Dispatchers.IO) {
					api.itemLookupApi.applySearchCriteria(itemId = item.id, replaceAllImages = replaceImages, data = result)
				}
				onApplied(result.name ?: item.name.orEmpty())
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Timber.w(e, "Identify apply failed for ${item.id}")
				phase = Phase.Failed(e.message ?: e.javaClass.simpleName)
			}
		}
	}

	DialogBase(
		visible = true,
		onDismissRequest = {
			when (phase) {
				// Back from a search or its results goes back to the form; the apply can't be cancelled
				Phase.Searching -> { job?.cancel(); phase = Phase.Form }
				is Phase.Results, is Phase.Failed -> phase = Phase.Form
				is Phase.Applying -> Unit
				Phase.Form -> onDismiss()
			}
		},
	) {
		Column(
			modifier = Modifier
				.width(640.dp)
				.heightIn(max = 620.dp)
				.background(JellyfinTheme.colorScheme.surface, RoundedCornerShape(16.dp))
				.padding(28.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			Text(
				stringResource(R.string.erg_identify_title, item.name.orEmpty()),
				fontSize = 22.sp,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)

			when (val p = phase) {
				Phase.Form -> IdentifyForm(
					name = name, onName = { name = it },
					year = year, onYear = { year = it.filter(Char::isDigit).take(4) },
					imdb = imdb, onImdb = { imdb = it },
					tmdb = tmdb, onTmdb = { tmdb = it },
					tvdb = tvdb, onTvdb = { tvdb = it },
					showTvdb = isSeries,
					replaceImages = replaceImages, onReplaceImages = { replaceImages = it },
					canSearch = name.isNotBlank() || imdb.isNotBlank() || tmdb.isNotBlank() || tvdb.isNotBlank(),
					onSearch = ::search,
					onCancel = onDismiss,
				)

				Phase.Searching -> {
					StatusMessage(stringResource(R.string.erg_identify_searching), holdFocus = true)
					BackButton(stringResource(R.string.lbl_cancel)) { job?.cancel(); phase = Phase.Form }
				}

				is Phase.Applying -> StatusMessage(stringResource(R.string.erg_identify_applying, p.result.name.orEmpty()), holdFocus = true)

				is Phase.Failed -> {
					StatusMessage(stringResource(R.string.erg_identify_failed, p.message))
					BackButton { phase = Phase.Form }
				}

				is Phase.Results -> {
					if (p.results.isEmpty()) {
						StatusMessage(stringResource(R.string.erg_identify_no_results))
						BackButton { phase = Phase.Form }
					} else {
						ResultList(p.results, onPick = ::apply)
					}
				}
			}
		}
	}
}

@Composable
private fun IdentifyForm(
	name: String, onName: (String) -> Unit,
	year: String, onYear: (String) -> Unit,
	imdb: String, onImdb: (String) -> Unit,
	tmdb: String, onTmdb: (String) -> Unit,
	tvdb: String, onTvdb: (String) -> Unit,
	showTvdb: Boolean,
	replaceImages: Boolean, onReplaceImages: (Boolean) -> Unit,
	canSearch: Boolean,
	onSearch: () -> Unit,
	onCancel: () -> Unit,
) {
	val searchFocus = rememberSettledFocus()

	Text(stringResource(R.string.erg_identify_hint), color = JellyfinTheme.colorScheme.listCaption, fontSize = 14.sp)
	LabeledField(stringResource(R.string.erg_identify_name)) {
		SeerrTextField(name, onName, Modifier.fillMaxWidth(), imeAction = ImeAction.Search, onSubmit = onSearch)
	}
	Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		LabeledField(stringResource(R.string.erg_identify_year), Modifier.width(120.dp)) {
			SeerrTextField(year, onYear, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Number, imeAction = ImeAction.Search, onSubmit = onSearch)
		}
		LabeledField(stringResource(R.string.erg_identify_imdb), Modifier.weight(1f)) {
			SeerrTextField(imdb, onImdb, Modifier.fillMaxWidth(), imeAction = ImeAction.Search, onSubmit = onSearch)
		}
		LabeledField(stringResource(R.string.erg_identify_tmdb), Modifier.weight(1f)) {
			SeerrTextField(tmdb, onTmdb, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Number, imeAction = ImeAction.Search, onSubmit = onSearch)
		}
		if (showTvdb) {
			LabeledField(stringResource(R.string.erg_identify_tvdb), Modifier.weight(1f)) {
				SeerrTextField(tvdb, onTvdb, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Number, imeAction = ImeAction.Search, onSubmit = onSearch)
			}
		}
	}
	ListButton(
		onClick = { onReplaceImages(!replaceImages) },
		headingContent = { Text(stringResource(R.string.erg_identify_replace_images)) },
		trailingContent = { Checkbox(checked = replaceImages) },
	)
	Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		Button(onClick = { if (canSearch) onSearch() }, modifier = Modifier.focusRequester(searchFocus)) {
			Text(stringResource(R.string.erg_identify_search))
		}
		Button(onClick = onCancel) { Text(stringResource(R.string.lbl_cancel)) }
	}
}

@Composable
private fun LabeledField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(label, fontSize = 13.sp, color = JellyfinTheme.colorScheme.listCaption)
		content()
	}
}

/**
 * Asks for focus once the dialog has settled. DialogBase focuses its first child when it opens,
 * so an immediate request loses to it.
 */
@Composable
private fun rememberSettledFocus(key: Any = Unit): FocusRequester {
	val focus = remember { FocusRequester() }
	LaunchedEffect(key) {
		delay(150)
		runCatching { focus.requestFocus() }
	}
	return focus
}

@Composable
private fun StatusMessage(text: String, holdFocus: Boolean = false) {
	// While a request runs, the message holds focus so the dialog never ends up with nothing focused
	val modifier = if (holdFocus) Modifier.focusRequester(rememberSettledFocus()).focusable() else Modifier
	Text(text, fontSize = 16.sp, color = JellyfinTheme.colorScheme.listCaption, modifier = modifier.padding(vertical = 8.dp))
}

@Composable
private fun BackButton(label: String? = null, onClick: () -> Unit) {
	Button(onClick = onClick, modifier = Modifier.focusRequester(rememberSettledFocus())) {
		Text(label ?: stringResource(R.string.erg_identify_back))
	}
}

@Composable
private fun ResultList(results: List<RemoteSearchResult>, onPick: (RemoteSearchResult) -> Unit) {
	val firstFocus = rememberSettledFocus(results)

	Text(stringResource(R.string.erg_identify_pick), color = JellyfinTheme.colorScheme.listCaption, fontSize = 14.sp)
	LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
		itemsIndexed(results) { index, result ->
			val year = result.productionYear ?: result.premiereDate?.year
			val caption = listOfNotNull(year?.toString(), result.searchProviderName).joinToString(" · ")
			ListButton(
				onClick = { onPick(result) },
				modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
				leadingContent = {
					Box(
						Modifier
							.size(width = 48.dp, height = 72.dp)
							.clip(RoundedCornerShape(4.dp))
							.background(JellyfinTheme.colorScheme.background),
						contentAlignment = Alignment.Center,
					) {
						if (!result.imageUrl.isNullOrBlank()) {
							AsyncImage(url = result.imageUrl, aspectRatio = 2f / 3f, modifier = Modifier.size(width = 48.dp, height = 72.dp))
						}
					}
				},
				headingContent = { Text(result.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
				captionContent = {
					Column {
						if (caption.isNotEmpty()) Text(caption)
						result.overview?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
					}
				},
			)
		}
	}
}
