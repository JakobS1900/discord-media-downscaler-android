package com.jakob.dmd.domain.compress

import com.jakob.dmd.domain.ffmpeg.FfmpegRunner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Animated GIF: ports compressor.py:_compress_gif. palettegen + paletteuse, with
 * progressive width fallback. Same width ladder as the Python source.
 */
@Singleton
class GifCompressor @Inject constructor(
    private val ffmpeg: FfmpegRunner,
) : Compressor {
    private val widths = listOf<Int?>(null, 640, 480, 360, 240)

    override suspend fun compress(
        src: File,
        srcDisplayName: String,
        limitBytes: Long,
        cacheDir: File,
        onProgress: OnProgress,
    ): CompressOutcome = coroutineScope {
        if (src.length() <= limitBytes) {
            val out = File(cacheDir, "out_${System.nanoTime()}.gif")
            src.copyTo(out, overwrite = true)
            onProgress(100, "Already fits — copied")
            return@coroutineScope CompressOutcome(out, out.length(), true, "gif")
        }

        val out = File(cacheDir, "out_${System.nanoTime()}.gif")
        var lastSize = 0L

        for ((idx, width) in widths.withIndex()) {
            coroutineContext.ensureActive()
            val label = if (width != null) "${width}px wide" else "original size"
            onProgress((idx * 85 / widths.size).coerceAtMost(85), "GIF: trying $label")

            val scalePart = if (width != null) "scale=$width:-1:flags=lanczos," else ""
            val vf = "${scalePart}split[s0][s1];" +
                    "[s0]palettegen=max_colors=128[p];" +
                    "[s1][p]paletteuse=dither=bayer:bayer_scale=5"

            if (out.exists()) out.delete()
            val rc = ffmpeg.run(
                listOf("-y", "-i", src.absolutePath, "-vf", vf, out.absolutePath)
            )
            if (rc != 0) continue

            lastSize = if (out.exists()) out.length() else 0L
            if (lastSize in 1..limitBytes) {
                onProgress(100, "GIF compressed ($label)")
                return@coroutineScope CompressOutcome(out, lastSize, true, "gif")
            }
        }

        onProgress(100, "GIF: best effort")
        CompressOutcome(out, lastSize, false, "gif")
    }
}
