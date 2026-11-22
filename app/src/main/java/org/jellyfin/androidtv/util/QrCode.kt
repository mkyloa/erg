package org.jellyfin.androidtv.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCode {
	/** Renders [content] as a square QR code bitmap of [size] pixels (dark modules on light). */
	@JvmStatic
	fun bitmap(content: String, size: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
		val matrix = QRCodeWriter().encode(
			content,
			BarcodeFormat.QR_CODE,
			size,
			size,
			mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
		)
		val bitmap = createBitmap(matrix.width, matrix.height)
		for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
			bitmap[x, y] = if (matrix[x, y]) dark else light
		}
		return bitmap
	}

	/** Link to the server's Quick Connect page; jellyfin-web pre-fills the code from the query. */
	@JvmStatic
	fun quickConnectUrl(serverAddress: String, code: String): String =
		"${serverAddress.trimEnd('/')}/web/#/quickconnect?code=$code"
}
