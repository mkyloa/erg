package org.jellyfin.androidtv.ui.playback.dovi

import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.profile.MediaCodecCapabilitiesTest

object DoviSupport {
	/**
	 * Whether Dolby Vision profile 7 should be played as its HDR10 base layer: the user allows it,
	 * the device can't decode profile 7 itself, and it can play HEVC HDR10.
	 */
	@JvmStatic
	fun useProfile7Fallback(userPreferences: UserPreferences, mediaTest: MediaCodecCapabilitiesTest): Boolean =
		userPreferences[UserPreferences.doviProfile7Fallback] &&
			!mediaTest.supportsHevcDolbyVisionEL() &&
			mediaTest.supportsHevcMain10() &&
			mediaTest.supportsHevcHDR10()

	@JvmStatic
	fun useProfile7Fallback(userPreferences: UserPreferences): Boolean =
		useProfile7Fallback(userPreferences, MediaCodecCapabilitiesTest(userPreferences[UserPreferences.softwareCodecsEnabled]))
}
