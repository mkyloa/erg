package org.jellyfin.androidtv.ui.base

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.AppTheme
import org.jellyfin.design.Tokens
import org.koin.mp.KoinPlatform

fun colorScheme(): ColorScheme = ColorScheme(
	background = Tokens.Color.colorGrey975,
	onBackground = Tokens.Color.colorBluegrey25,
	button = Color(0xB3747474),
	onButton = Color(0xFFDDDDDD),
	buttonFocused = Color(0xE6CCCCCC),
	onButtonFocused = Color(0xFF444444),
	buttonDisabled = Color(0x33747474),
	onButtonDisabled = Color(0xFF686868),
	buttonActive = Color(0x4DCCCCCC),
	onButtonActive = Color(0xFFDDDDDD),
	input = Color(0xB3747474),
	onInput = Color(0xE6CCCCCC),
	inputFocused = Color(0xE6CCCCCC),
	onInputFocused = Color(0xFFDDDDDD),
	rangeControlBackground = Tokens.Color.colorBluegrey700,
	rangeControlFill = Tokens.Color.colorCyan500,
	rangeControlKnob = Tokens.Color.colorBluegrey100,
	seekbarBuffer = Tokens.Color.colorBluegrey300,
	recording = Tokens.Color.colorRed300,
	onRecording = Tokens.Color.colorRed25,
	badge = Tokens.Color.colorCyan500,
	onBadge = Tokens.Color.colorBluegrey100,
	listHeader = Tokens.Color.colorGrey50,
	listOverline = Tokens.Color.colorGrey500,
	listHeadline = Tokens.Color.colorGrey25,
	listCaption = Tokens.Color.colorGrey200,
	listButton = Color.Transparent,
	listButtonFocused = Tokens.Color.colorBluegrey800,
	surface = Tokens.Color.colorBluegrey900,
	scrim = Tokens.Color.colorBlack.copy(alpha = 0.67f),
)

@Immutable
data class ColorScheme(
	val background: Color,
	val onBackground: Color,

	val button: Color,
	val onButton: Color,
	val buttonFocused: Color,
	val onButtonFocused: Color,
	val buttonDisabled: Color,
	val onButtonDisabled: Color,
	val buttonActive: Color,
	val onButtonActive: Color,

	val input: Color,
	val onInput: Color,
	val inputFocused: Color,
	val onInputFocused: Color,

	val rangeControlBackground: Color,
	val rangeControlFill: Color,
	val rangeControlKnob: Color,
	val seekbarBuffer: Color,

	val recording: Color,
	val onRecording: Color,

	val badge: Color,
	val onBadge: Color,

	val listHeader: Color,
	val listOverline: Color,
	val listHeadline: Color,
	val listCaption: Color,
	val listButton: Color,
	val listButtonFocused: Color,

	val surface: Color,
	val scrim: Color,
)

/** Gruvbox dark (github.com/morhetz/gruvbox) for Compose screens. */
fun gruvboxColorScheme(): ColorScheme = ColorScheme(
	background = Color(0xFF282828),
	onBackground = Color(0xFFEBDBB2),
	button = Color(0xB33C3836),
	onButton = Color(0xFFD5C4A1),
	buttonFocused = Color(0xFFEBDBB2),
	onButtonFocused = Color(0xFF282828),
	buttonDisabled = Color(0x333C3836),
	onButtonDisabled = Color(0xFF7C6F64),
	buttonActive = Color(0x4DFABD2F),
	onButtonActive = Color(0xFFEBDBB2),
	input = Color(0xFF504945),
	onInput = Color(0xFFD5C4A1),
	inputFocused = Color(0xFFFABD2F),
	onInputFocused = Color(0xFFEBDBB2),
	rangeControlBackground = Color(0xFF504945),
	rangeControlFill = Color(0xFFFABD2F),
	rangeControlKnob = Color(0xFFEBDBB2),
	seekbarBuffer = Color(0xFF7C6F64),
	recording = Color(0xFFFB4934),
	onRecording = Color(0xFFEBDBB2),
	badge = Color(0xFFFE8019),
	onBadge = Color(0xFF282828),
	listHeader = Color(0xFFEBDBB2),
	listOverline = Color(0xFF928374),
	listHeadline = Color(0xFFEBDBB2),
	listCaption = Color(0xFFA89984),
	listButton = Color.Transparent,
	listButtonFocused = Color(0xFF504945),
	surface = Color(0xFF3C3836),
	scrim = Color(0xAB1D2021),
)

/** Compose colors for an app theme. Themes without their own scheme use the default. */
fun colorSchemeFor(theme: AppTheme): ColorScheme = when (theme) {
	AppTheme.GRUVBOX -> gruvboxColorScheme()
	else -> colorScheme()
}

private val schemeCache = mutableMapOf<AppTheme, ColorScheme>()

/**
 * The scheme for the theme the user picked. Read on every access rather than cached once, so a
 * theme change applies to new screens without restarting the app; also covers the many small
 * Compose views embedded in leanback screens, which have no JellyfinTheme around them.
 */
internal fun currentAppColorScheme(): ColorScheme {
	val prefs = runCatching { KoinPlatform.getKoinOrNull()?.getOrNull<UserPreferences>() }.getOrNull()
		?: return colorScheme()
	val theme = prefs[UserPreferences.appTheme]
	return schemeCache.getOrPut(theme) { colorSchemeFor(theme) }
}

/** Set by [JellyfinTheme]; when absent the user's app theme decides. */
val LocalColorScheme = staticCompositionLocalOf<ColorScheme?> { null }
