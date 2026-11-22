package org.jellyfin.androidtv.seerr.ui

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
	val focused by interactionSource.collectIsFocusedAsState()

	val (accent, content) = when {
		focused -> JellyfinTheme.colorScheme.inputFocused to JellyfinTheme.colorScheme.onInputFocused
		else -> JellyfinTheme.colorScheme.input to JellyfinTheme.colorScheme.onInput
	}

	ProvideTextStyle(LocalTextStyle.current.copy(color = content, fontSize = 16.sp)) {
		BasicTextField(
			modifier = modifier,
			value = value,
			singleLine = true,
			interactionSource = interactionSource,
			onValueChange = onValueChange,
			keyboardActions = KeyboardActions { onSubmit() },
			keyboardOptions = KeyboardOptions.Default.copy(
				keyboardType = if (password) KeyboardType.Password else keyboardType,
				imeAction = imeAction,
				autoCorrectEnabled = false,
				showKeyboardOnFocus = true,
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
