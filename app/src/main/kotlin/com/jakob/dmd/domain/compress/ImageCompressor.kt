package com.jakob.dmd.domain.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jakob.dmd.util.MediaTypeDetector
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Image compression: ports compressor.py's _compress_image / _jpeg_search / _webp_search.
 *
 * Differences from desktop:
 *  - Loads via BitmapFactory.Options.inSampleSize when source is huge, so we never
 *    OOM on a 50 MP photo. (Fixes the HIGH-severity Image.new+putdata blow-up from
 *    main.py:152 found in code review.)
 *  - Tracks min_q_tried to correctly trigger the rescale branch when q==1 still
 *    overflows. (Fixes the HIGH-severity bug in compressor.py:188-224.)
 *  - PNG path: re-encodes to PNG via Bitmap.compress(PNG, 100). Android's PNG
 *    encoder is not as small as Pillow's, so for any meaningful budget we fall
 *    through to the WebP/JPEG branch quickly.
 */
@Singleton
class ImageCompressor @Inject constructor() : Compressor {

    private val maxDecodeDim = 8192   // hard cap; bigger sources get inSampleSize'd

    override suspend fun compress(
        src: File,
        srcDisplayName: String,
        limitBytes: Long,
        cacheDir: File,
        onProgress: OnProgress,
    ): CompressOutcome = coroutineScope {
        val srcExt = MediaTypeDetector.extOf(srcDisplayName).ifEmpty {
            MediaTypeDetector.extOf(src.name)
        }
        val srcSize = src.length()

        // No-op fast path
        if (srcSize <= limitBytes) {
            val outExt = if (srcExt.isEmpty()) "bin" else srcExt
            val out = File(cacheDir, "out_${System.nanoTime()}.$outExt")
            src.copyTo(out, overwrite = true)
            onProgress(100, "Already fits — copied")
            return@coroutineScope CompressOutcome(out, out.length(), true, outExt)
        }

        // Probe size to decide inSampleSize
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = if (hasAlphaForExt(srcExt)) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, opts)
            ?: throw IllegalStateException("Failed to decode image")

        coroutineContext.ensureActive()
        try {
            val hasAlpha = bmp.hasAlpha()
            return@coroutineScope when {
                srcExt == "png" -> {
                    // Try lossless WebP first, then fall back to JPEG (no alpha) or lossy WebP (alpha)
                    onProgress(20, "PNG: trying lossless WebP")
                    val webPLossless = encodeWebPLossless(bmp)
                    if (webPLossless.size <= limitBytes) {
                        val out = File(cacheDir, "out_${System.nanoTime()}.webp")
                        out.writeBytes(webPLossless)
                        onProgress(100, "PNG → lossless WebP")
                        CompressOutcome(out, out.length(), true, "webp")
                    } else if (hasAlpha) {
                        webpSearch(bmp, limitBytes, cacheDir, onProgress)
                    } else {
                        jpegSearch(bmp, limitBytes, cacheDir, onProgress, note = "PNG→JPEG fallback")
                    }
                }
                srcExt == "webp" -> webpSearch(bmp, limitBytes, cacheDir, onProgress)
                srcExt == "jpg" || srcExt == "jpeg" -> jpegSearch(bmp, limitBytes, cacheDir, onProgress)
                else -> jpegSearch(bmp, limitBytes, cacheDir, onProgress)
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun sampleSizeFor(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        var dw = w
        var dh = h
        while (dw > maxDecodeDim || dh > maxDecodeDim) {
            sample *= 2
            dw /= 2
            dh /= 2
        }
        return sample
    }

    private fun hasAlphaForExt(ext: String) = ext == "png" || ext == "webp"

    private fun encodeWebPLossless(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        // On API 30+, WEBP_LOSSLESS is preferred; pre-30 it's quality-100 plain WEBP.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        } else {
            @Suppress("DEPRECATION")
            bmp.compress(Bitmap.CompressFormat.WEBP, 100, out)
        }
        return out.toByteArray()
    }

    private suspend fun jpegSearch(
        srcBmp: Bitmap,
        limitBytes: Long,
        cacheDir: File,
        onProgress: OnProgress,
        note: String = "",
    ): CompressOutcome {
        var lo = 1
        var hi = 95
        var best: ByteArray? = null
        var scale = 1.0
        var minQTried = Int.MAX_VALUE
        val label = if (note.isNotEmpty()) " ($note)" else ""

        var bmp = srcBmp
        var ownsBmp = false

        try {
            for (i in 0 until 16) {
                coroutineContext.ensureActive()
                val q = (lo + hi) / 2
                onProgress((i * 85 / 16).coerceAtMost(85), "Image quality $q%$label")

                val buf = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, buf)
                val bytes = buf.toByteArray()
                val size = bytes.size.toLong()
                if (q < minQTried) minQTried = q

                if (size <= limitBytes) {
                    best = bytes
                    if (size >= limitBytes * 0.85) break
                    lo = q + 1
                } else {
                    hi = q - 1
                }

                if (lo > hi) {
                    // Fix from code review: trigger rescale based on minQTried, not last loop q.
                    if (minQTried <= 1 && size > limitBytes && scale > 0.0625) {
                        scale *= 0.5
                        val nw = (srcBmp.width * scale).toInt().coerceAtLeast(8)
                        val nh = (srcBmp.height * scale).toInt().coerceAtLeast(8)
                        if (ownsBmp) bmp.recycle()
                        bmp = Bitmap.createScaledBitmap(srcBmp, nw, nh, true)
                        ownsBmp = true
                        lo = 1
                        hi = 95
                        minQTried = Int.MAX_VALUE
                    } else {
                        break
                    }
                }
            }

            if (best == null) {
                val emergency = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 1, emergency)
                best = emergency.toByteArray()
            }

            val out = File(cacheDir, "out_${System.nanoTime()}.jpg")
            out.writeBytes(best!!)
            val metLimit = out.length() <= limitBytes
            onProgress(100, if (metLimit) "Image compressed" else "Image: best effort")
            return CompressOutcome(out, out.length(), metLimit, "jpg")
        } finally {
            if (ownsBmp) bmp.recycle()
        }
    }

    private suspend fun webpSearch(
        bmp: Bitmap,
        limitBytes: Long,
        cacheDir: File,
        onProgress: OnProgress,
    ): CompressOutcome {
        var lo = 1
        var hi = 95
        var best: ByteArray? = null

        for (i in 0 until 14) {
            coroutineContext.ensureActive()
            val q = (lo + hi) / 2
            onProgress((i * 85 / 14).coerceAtMost(85), "WebP quality $q%")

            val buf = ByteArrayOutputStream()
            @Suppress("DEPRECATION") // WEBP (lossy) is the only universally-available variant
            bmp.compress(Bitmap.CompressFormat.WEBP, q, buf)
            val bytes = buf.toByteArray()
            val size = bytes.size.toLong()

            if (size <= limitBytes) {
                best = bytes
                if (size >= limitBytes * 0.85) break
                lo = q + 1
            } else {
                hi = q - 1
            }
            if (lo > hi) break
        }

        if (best == null) {
            val emergency = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            bmp.compress(Bitmap.CompressFormat.WEBP, 1, emergency)
            best = emergency.toByteArray()
        }

        val out = File(cacheDir, "out_${System.nanoTime()}.webp")
        out.writeBytes(best!!)
        val metLimit = out.length() <= limitBytes
        onProgress(100, if (metLimit) "WebP compressed" else "WebP: best effort")
        return CompressOutcome(out, out.length(), metLimit, "webp")
    }
}
