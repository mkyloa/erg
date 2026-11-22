package org.jellyfin.androidtv.erg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.home.PinnedRow
import org.jellyfin.androidtv.ui.home.PinnedRowType
import org.jellyfin.androidtv.ui.home.PinnedRows
import org.jellyfin.androidtv.update.ErgUpdater
import org.jellyfin.androidtv.util.QrCode
import java.util.UUID

class ErgFeatureTests : FunSpec({
	test("update versions compare semantically") {
		ErgUpdater.isNewer("1.0.1", "1.0.0") shouldBe true
		ErgUpdater.isNewer("v1.10.0", "1.9.9") shouldBe true
		ErgUpdater.isNewer("1.0.0", "1.0.0") shouldBe false
		ErgUpdater.isNewer("1.0.0", "1.0.0-beta.2") shouldBe true
		ErgUpdater.isNewer("1.0.0-beta.2", "1.0.0") shouldBe false
		ErgUpdater.isNewer("1.0.0-beta.3", "1.0.0-beta.2") shouldBe true
		ErgUpdater.isNewer("0.9", "1.0.0") shouldBe false
	}

	test("pinned rows query the right items") {
		val id = UUID.randomUUID()
		val user = UUID.randomUUID()

		val collection = PinnedRows.query(PinnedRow(PinnedRowType.COLLECTION, id.toString(), "Bond"), user).shouldNotBeNull()
		collection.parentId shouldBe id
		collection.genreIds.shouldBeNull()

		val genre = PinnedRows.query(PinnedRow(PinnedRowType.GENRE, id.toString(), "Sci-Fi"), user).shouldNotBeNull()
		genre.genreIds shouldBe setOf(id)
		genre.recursive shouldBe true
		genre.parentId.shouldBeNull()

		val studio = PinnedRows.query(PinnedRow(PinnedRowType.STUDIO, id.toString().replace("-", ""), "A24"), user).shouldNotBeNull()
		studio.studioIds shouldBe setOf(id)

		PinnedRows.query(PinnedRow(PinnedRowType.PLAYLIST, "not-an-id", "x"), user).shouldBeNull()
	}

	test("quick connect QR opens the server page with the code") {
		QrCode.quickConnectUrl("https://jf.example.com/", "123456") shouldBe "https://jf.example.com/web/#/quickconnect?code=123456"
		QrCode.quickConnectUrl("http://10.0.0.2:8096", "654321") shouldBe "http://10.0.0.2:8096/web/#/quickconnect?code=654321"
	}
})
