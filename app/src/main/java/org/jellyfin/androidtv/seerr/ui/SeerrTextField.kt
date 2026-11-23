package org.jellyfin.androidtv.seerr.ui

import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text

/** Single-line input matching the app's search field (rounded outline that lights up on focus). */
@Composable
fun SeerrTextField(
	value: String,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	placeholder: String? = null,
	icon: Painter? = null,
	keyboardType: KeyboardType = KeyboardType.Text,
	imeAction: ImeAction = ImeAction.Done,
	password: Boolean = false,
	onSubmit: () -> Unit = {},
) {
	val interactionSource = remember { MutableInteractionSource() }
	val textFocused by interactionSource.collectIsFocusedAsState()
	val shellInteraction = remember { MutableInteractionSource() }
	val shellFocused by shellInteraction.collectIsFocusedAsState()
	val focused = textFocused || shellFocused

	// On a TV, focus passes through this field all the time and the system keyboard opens whenever
	// the text input gets focus. So the input only becomes focusable after OK is pressed on it.
	var editing by remember { mutableStateOf(false) }
	val textFocus = remember { FocusRequester() }
	val keyboard = LocalSoftwareKeyboardController.current
	val focusManager = LocalFocusManager.current
	LaunchedEffect(editing) {
		if (editing) {
			runCatching { textFocus.requestFocus() }
			keyboard?.show()
		}
	}

	val (accent, content) = when {
		focused -> JellyfinTheme.colorScheme.inputFocused to JellyfinTheme.colorScheme.onInputFocused
		else -> JellyfinTheme.colorScheme.input to JellyfinTheme.colorScheme.onInput
	}

	ProvideTextStyle(LocalTextStyle.current.copy(color = content, fontSize = 16.sp)) {
		BasicTextField(
			modifier = modifier
				.onPreviewKeyEvent { event ->
					val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
					val vertical = when (event.key) {
						Key.DirectionUp -> FocusDirection.Up
						Key.DirectionDown -> FocusDirection.Down
						else -> null
					}
					when {
						// The text input would keep up/down for its cursor; leave the field instead
						editing && vertical != null -> {
							if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(vertical)
							true
						}
						!ok -> false
						// Already typing: OK brings the keyboard back if Back closed it
						editing -> { if (event.type == KeyEventType.KeyUp) keyboard?.show(); false }
						event.type == KeyEventType.KeyUp -> { editing = true; true }
						else -> true
					}
				}
				.focusable(interactionSource = shellInteraction)
				.focusRequester(textFocus)
				.focusProperties { canFocus = editing }
				.onFocusChanged { if (editing && !it.isFocused && !it.hasFocus) editing = false },
			value = value,
			singleLine = true,
			interactionSource = interactionSource,
			onValueChange = onValueChange,
			keyboardActions = KeyboardActions { onSubmit() },
			keyboardOptions = KeyboardOptions.Default.copy(
				keyboardType = if (password) KeyboardType.Password else keyboardType,
				imeAction = imeAction,
				autoCorrectEnabled = false,
				// On a TV, focus moves through the field constantly; open the keyboard only on OK
				showKeyboardOnFocus = false,
			),
			visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
			textStyle = LocalTextStyle.current,
			cursorBrush = SolidColor(accent),
			decorationBox = { innerTextField ->
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier
						.border(2.dp, accent, RoundedCornerShape(percent = 30))
						.padding(12.dp)
				) {
					if (icon != null) {
						Icon(icon, contentDescription = null)
						Spacer(Modifier.width(12.dp))
					}
					Box {
						if (value.isEmpty() && placeholder != null) {
							Text(placeholder, color = content.copy(alpha = 0.5f))
						}
						innerTextField()
					}
				}
			}
		)
	}
}
