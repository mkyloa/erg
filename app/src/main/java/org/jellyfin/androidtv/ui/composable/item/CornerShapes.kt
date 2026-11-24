package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.CornerStyle
import org.jellyfin.preference.Preference
import org.koin.compose.koinInject

/** Erg: the parts of the UI whose corners can be changed in Settings > Customization. */
enum class CornerTarget(
	val preference: Preference<CornerStyle>,
	val titleRes: Int,
	val options: List<CornerStyle>,
) {
	CARDS(UserPreferences.cardCorners, R.string.erg_card_corners, CornerStyle.entries - CornerStyle.ROUND),
	BANNER(UserPreferences.heroCorners, R.string.erg_hero_corners, CornerStyle.entries - CornerStyle.ROUND),
	BUTTONS(UserPreferences.buttonCorners, R.string.erg_button_corners, CornerStyle.entries.toList());

	/**
	 * The style picked in this session. Composables read it as state, so a change in settings
	 * reshapes what is already on screen; until then the saved preference is used.
	 */
	val picked: MutableState<CornerStyle?> = mutableStateOf(null)
}

fun CornerStyle.toShape(): Shape = if (this == CornerStyle.ROUND) RoundedCornerShape(percent = 50) else RoundedCornerShape(radiusDp.dp)

@Composable
fun rememberCornerShape(target: CornerTarget): Shape {
	val userPreferences = koinInject<UserPreferences>()
	val style = target.picked.value ?: userPreferences[target.preference]
	return remember(style) { style.toShape() }
}

/** Erg: the card corner shape picked in Settings > Customization > Card corners. */
@Composable
fun rememberCardShape(): Shape = rememberCornerShape(CornerTarget.CARDS)

/** Erg: the home banner corner shape. */
@Composable
fun rememberBannerShape(): Shape = rememberCornerShape(CornerTarget.BANNER)

/** Erg: the shape of top bar buttons and the banner's buttons. */
@Composable
fun rememberButtonShape(): Shape = rememberCornerShape(CornerTarget.BUTTONS)
