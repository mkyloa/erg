package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.CardCorners
import org.koin.compose.koinInject

/**
 * Erg: the card corners picked in this session. Cards read it as Compose state, so changing the
 * setting reshapes cards that are already on screen; until then the saved preference is used.
 */
object CardCornersState {
	val picked = mutableStateOf<CardCorners?>(null)
}

/** Erg: the card corner shape picked in Settings > Customization > Card corners. */
@Composable
fun rememberCardShape(): Shape {
	val userPreferences = koinInject<UserPreferences>()
	val corners = CardCornersState.picked.value ?: userPreferences[UserPreferences.cardCorners]
	return remember(corners) { RoundedCornerShape(corners.radiusDp.dp) }
}
