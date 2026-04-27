package com.jakob.dmd.domain.compress

import com.jakob.dmd.domain.ffmpeg.FfmpegRunner
import com.jakob.dmd.domain.ffmpeg.MediaProbe
import com.jakob.dmd.util.MediaTypeDetector
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Audio: ports compressor.py:_compress_audio.
 *  - Lossless sources (.wav/.flac) → libopus .ogg
 *  - .mp3/.aac/.m4a → libmp3lame .mp3
 *  - .ogg + others → libvorbis .ogg
 *  - Binary-search bitrate, then fall back to mono if stereo can't hit it.
 */
@Singleton
class AudioCompressor @Inject constructor(
    private val ffmpeg: FfmpegRunner,
    private val probe: MediaProbe,
) : Compressor {

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
        val isLossless = srcExt == "wav" || srcExt == "flac"
        val outExt = audioOutExt(srcExt, isLossless)

        if (src.length() <= limitBytes) {
            val out = File(cacheDir, "out_${System.nanoTime()}.$outExt")
            src.copyTo(out, overwrite = true)
            onProgress(100, "Already fits — copied")
            return@coroutineScope CompressOutcome(out, out.length(), true, outExt)
        }

        val info = probe.probe(src)
        val duration = info.durationSeconds
        val finalOut = File(cacheDir, "out_${System.nanoTime()}.$outExt")

        if (duration < 0.1) {
            // Fallback: single shot at 128k
            encodeAudio(src, finalOut, kbps = 128, isLossless = isLossless, mono = false)
            val size = if (finalOut.exists()) finalOut.length() else 0L
            val met = size in 1..limitBytes
            onProgress(100, if (met) "Audio compressed" else "Audio: best effort")
            return@coroutineScope CompressOutcome(finalOut, size, met, outExt)
        }

        var targetKbps = ((limitBytes * 8L) / duration / 1000.0 * 0.97).toInt()
        targetKbps = (targetKbps / 8) * 8
        targetKbps = targetKbps.coerceIn(16, 320)
        onProgress(10, "Audio: targeting ${targetKbps} kbps")

        var bestKbps: Int? = null
        var bestMono = false

        outer@ for (mono in listOf(false, true)) {
            var lo = 16
            var hi = 320
            var foundKbps: Int? = null
            var kbps = targetKbps
            val suffix = if (mono) " (mono)" else ""

            for (i in 0 until 9) {
                coroutineContext.ensureActive()
                onProgress((i * 70 / 9 + 10).coerceAtMost(80), "Audio: $kbps kbps$suffix")

                val tmp = File(cacheDir, "audio_try_${System.nanoTime()}.$outExt")
                encodeAudio(src, tmp, kbps, isLossless, mono)
                if (!tmp.exists() || tmp.length() < 512) {
                    hi = kbps - 8
                } else {
                    val size = tmp.length()
                    tmp.delete()
                    if (size <= limitBytes) {
                        foundKbps = kbps
                        if (size >= limitBytes * 0.85) break
                        lo = kbps + 8
                    } else {
                        hi = kbps - 8
                    }
                }

                if (lo > hi) break
                kbps = (((lo + hi) / 2) / 8 * 8).coerceAtLeast(16)
            }

            if (foundKbps != null) {
                bestKbps = foundKbps
                bestMono = mono
                break@outer
            }
        }

        if (bestKbps != null) {
            onProgress(90, "Audio: final encode at ${bestKbps} kbps")
            encodeAudio(src, finalOut, bestKbps!!, isLossless, bestMono)
        } else {
            onProgress(90, "Audio: best effort (minimum bitrate)")
            encodeAudio(src, finalOut, 16, isLossless, mono = true)
        }

        val size = if (finalOut.exists()) finalOut.length() else 0L
        val met = size in 1..limitBytes
        val tail = if (isLossless) " (→ Opus OGG)" else ""
        onProgress(100, if (met) "Audio compressed$tail" else "Audio: best effort$tail")
        CompressOutcome(finalOut, size, met, outExt)
    }

    private fun audioOutExt(srcExt: String, isLossless: Boolean): String = when {
        isLossless -> "ogg"
        srcExt == "mp3" || srcExt == "aac" || srcExt == "m4a" -> "mp3"
        else -> "ogg"
    }

    private suspend fun encodeAudio(
        src: File,
        out: File,
        kbps: Int,
        isLossless: Boolean,
        mono: Boolean,
    ) {
        val srcExt = MediaTypeDetector.extOf(src.name)
        val channels = if (mono) listOf("-ac", "1") else emptyList()
        if (out.exists()) out.delete()

        val args: List<String> = when {
            isLossless -> listOf(
                "-y", "-i", src.absolutePath,
                "-c:a", "libopus", "-b:a", "${kbps}k",
                *channels.toTypedArray(), "-ar", "48000",
                "-map_metadata", "-1",
                out.absolutePath,
            )
            srcExt == "mp3" || srcExt == "aac" || srcExt == "m4a" -> listOf(
                "-y", "-i", src.absolutePath,
                "-c:a", "libmp3lame", "-b:a", "${kbps}k",
                *channels.toTypedArray(),
                "-map_metadata", "-1",
                out.absolutePath,
            )
            else -> listOf(
                "-y", "-i", src.absolutePath,
                "-c:a", "libvorbis", "-b:a", "${kbps}k",
                *channels.toTypedArray(),
                "-map_metadata", "-1",
                out.absolutePath,
            )
        }
        ffmpeg.run(args)
    }
}
