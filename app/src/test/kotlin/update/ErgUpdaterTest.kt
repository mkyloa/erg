package org.jellyfin.androidtv.update

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ErgUpdaterTest : FunSpec({
	test("newer versions compare above older ones, releases above pre-releases") {
		ErgUpdater.isNewer("0.1.1", "0.1.0") shouldBe true
		ErgUpdater.isNewer("v0.2.0", "0.1.9") shouldBe true
		ErgUpdater.isNewer("0.1.0", "0.1.0") shouldBe false
		ErgUpdater.isNewer("0.1.0", "0.1.0-beta.1") shouldBe true
		ErgUpdater.isNewer("0.1.0-beta.1", "0.1.0") shouldBe false
	}

	test("GitHub generated notes become plain text") {
		val body = """
			## What's Changed
			* Fix home layout by @mkyloa in https://github.com/mkyloa/erg/pull/3
			* Seerr **badges** for [requests](https://example.com)


			**Full Changelog**: https://github.com/mkyloa/erg/compare/v0.1.0...v0.1.1
		""".trimIndent()

		ErgUpdater.readableNotes(body) shouldBe "What's Changed\n• Fix home layout\n• Seerr badges for requests"
	}

	test("tag notes drop the title line") {
		ErgUpdater.readableNotes("Erg 0.1.2\n\n- Skip this version\n- Pin to home") shouldBe "• Skip this version\n• Pin to home"
		ErgUpdater.readableNotes("") shouldBe null
		ErgUpdater.readableNotes(null) shouldBe null
	}
})
