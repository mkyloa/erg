package org.jellyfin.androidtv.ui.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.ui.playback.dovi.asBaseLayer
import org.jellyfin.androidtv.ui.playback.dovi.isDolbyVisionProfile7
import org.jellyfin.androidtv.util.profile.MediaCodecCapabilitiesTest
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.androidtv.util.profile.createMpvDeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

private fun dv(codecs: String) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs).build()

/** A device with HEVC HDR10 but no Dolby Vision decoder. */
private fun hdr10OnlyDevice() = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
	every { supportsHevc() } returns true
	every { supportsHevcMain10() } returns true
	every { supportsHevcHDR10() } returns true
	every { supportsHevcDolbyVision() } returns false
	every { supportsHevcDolbyVisionEL() } returns false
	every { supportsAVC() } returns true
}

private fun profileFor(fallback: Boolean) = createDeviceProfile(
	mediaTest = hdr10OnlyDevice(),
	maxBitrate = 100_000_000,
	isAC3PrefEnabled = true,
	isEAC3PrefEnabled = true,
	isDTSPrefEnabled = false,
	isTrueHDPrefEnabled = false,
	downMixAudio = false,
	assDirectPlay = false,
	pgsDirectPlay = true,
	userAVCLevel = null,
	userHEVCLevel = null,
	forceEnabledHdr = emptySet(),
	forceDisabledHdr = emptySet(),
	doviProfile7Fallback = fallback,
)

/** Range types the HEVC codec profile tells the server it can't play. */
private fun unsupportedHevcRanges(fallback: Boolean): List<String> =
	profileFor(fallback).codecProfiles
		.filter { it.codec == "hevc" }
		.flatMap { it.applyConditions }
		.flatMap { it.value.orEmpty().split('|') }

class ErgPlaybackTests : FunSpec({
	test("Dolby Vision profile 7 is detected from the codecs string") {
		dv("dvhe.07.06").isDolbyVisionProfile7() shouldBe true
		dv("dvh1.07.06").isDolbyVisionProfile7() shouldBe true
		dv("dvhe.08.06").isDolbyVisionProfile7() shouldBe false
		Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153").build().isDolbyVisionProfile7() shouldBe false
	}

	test("profile 7 is presented as its HEVC base layer, other formats untouched") {
		val p7 = dv("dvhe.07.06")
		val base = p7.asBaseLayer()
		base.sampleMimeType shouldBe MimeTypes.VIDEO_H265
		base.codecs.shouldBeNull()

		val p8 = dv("dvhe.08.06")
		p8.asBaseLayer() shouldBeSameInstanceAs p8
	}

	test("device profile direct plays profile 7 only when the fallback is on") {
		unsupportedHevcRanges(fallback = false) shouldContain "DOVIWithEL"
		unsupportedHevcRanges(fallback = true) shouldNotContain "DOVIWithEL"
		// Profile 5 still needs a real Dolby Vision decoder either way
		unsupportedHevcRanges(fallback = true) shouldContain "DOVI"
	}

	test("mpv profile direct plays any container and codec") {
		val profile = createMpvDeviceProfile(maxBitrate = 50_000_000)
		val video = profile.directPlayProfiles.single { it.type == DlnaProfileType.VIDEO }
		video.container.orEmpty() shouldBe ""
		video.videoCodec.isNullOrEmpty() shouldBe true
		video.audioCodec.isNullOrEmpty() shouldBe true
		profile.maxStreamingBitrate shouldBe 50_000_000

		val ass = profile.subtitleProfiles.filter { it.format == "ass" }.map { it.method }
		ass shouldContain SubtitleDeliveryMethod.EMBED
		ass shouldContain SubtitleDeliveryMethod.EXTERNAL
		val pgs = profile.subtitleProfiles.filter { it.format == "pgssub" }.map { it.method }
		pgs shouldContain SubtitleDeliveryMethod.EMBED
	}
})
