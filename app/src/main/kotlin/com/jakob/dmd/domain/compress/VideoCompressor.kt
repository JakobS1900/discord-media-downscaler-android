package com.jakob.dmd.domain.compress

import com.jakob.dmd.domain.ffmpeg.FfmpegRunner
import com.jakob.dmd.domain.ffmpeg.MediaProbe
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Video: ports compressor.py:_compress_video / _video_twopass / _video_fallback.
 *  - libx264 two-pass with progressive resolution + bitrate backoff.
 *  - Single-pass CRF fallback when duration is unknown.
 *  - Final pass strips audio if all else fails.
 */
@Singleton
class VideoCompressor @Inject constructor(
    private val ffmpeg: FfmpegRunner,
    private val probe: MediaProbe,
) : Compressor {

    private data class Step(val scaleFilter: String?, val label: String)

    private val baseSteps = listOf(
        Step(null, "original size"),
        Step("1280:-2", "1280px wide"),
        Step("854:-2", "854px wide"),
        Step("640:-2", "640px wide"),
        Step("480:-2", "480px wide"),
        Step("360:-2", "360px wide"),
        Step("240:-2", "240px wide"),
        Step("240:-2,fps=15", "240px @ 15fps"),
        Step("240:-2,fps=10", "240px @ 10fps"),
    )

    override suspend fun compress(
        src: File,
        srcDisplayName: String,
        limitBytes: Long,
        cacheDir: File,
        onProgress: OnProgress,
    ): CompressOutcome = coroutineScope {
        if (src.length() <= limitBytes) {
            val out = File(cacheDir, "out_${System.nanoTime()}.mp4")
            src.copyTo(out, overwrite = true)
            onProgress(100, "Already fits — copied")
            return@coroutineScope CompressOutcome(out, out.length(), true, "mp4")
        }

        val info = probe.probe(src)
        val hasAudio = info.hasAudio
        val duration = info.durationSeconds

        if (duration < 0.1) {
            return@coroutineScope videoFallback(src, limitBytes, cacheDir, hasAudio, onProgress)
        }

        val steps = if (hasAudio) {
            baseSteps + Step("240:-2,fps=10", "240px @ 10fps (no audio)")
        } else baseSteps

        var bestEffortPath: File? = null
        var bestEffortSize = Long.MAX_VALUE

        for ((stepIdx, step) in steps.withIndex()) {
            coroutineContext.ensureActive()
            val stripAudio = !hasAudio || (hasAudio && stepIdx == steps.size - 1)
            if (stepIdx > 0) {
                onProgress(
                    (stepIdx.toDouble() / steps.size * 90).toInt(),
                    "Still too large — trying ${step.label}…"
                )
            }

            val (winner, effortPath, effortSize) = videoTwoPass(
                src = src,
                limitBytes = limitBytes,
                duration = duration,
                hasAudio = hasAudio && !stripAudio,
                step = step,
                stepIdx = stepIdx,
                totalSteps = steps.size,
                cacheDir = cacheDir,
                onProgress = onProgress,
            )
            if (winner != null) return@coroutineScope CompressOutcome(winner, winner.length(), true, "mp4")
            if (effortPath != null && effortSize < bestEffortSize) {
                bestEffortPath?.delete()
                bestEffortPath = effortPath
                bestEffortSize = effortSize
            } else if (effortPath != null && effortPath != bestEffortPath) {
                effortPath.delete()
            }
        }

        val finalEffort = bestEffortPath ?: File(cacheDir, "out_${System.nanoTime()}.mp4")
        val mb = bestEffortSize / 1024.0 / 1024.0
        onProgress(99, "Best effort: %.1f MB (target not fully reached)".format(mb))
        CompressOutcome(finalEffort, if (finalEffort.exists()) finalEffort.length() else 0L, false, "mp4")
    }

    private fun pickAudioKbps(totalBudgetKbps: Int): Pair<Int, Boolean> = when {
        totalBudgetKbps >= 160 -> 128 to false
        totalBudgetKbps >= 80 -> 64 to false
        totalBudgetKbps >= 40 -> 32 to false
        else -> 16 to true
    }

    private suspend fun videoTwoPass(
        src: File,
        limitBytes: Long,
        duration: Double,
        hasAudio: Boolean,
        step: Step,
        stepIdx: Int,
        totalSteps: Int,
        cacheDir: File,
        onProgress: OnProgress,
    ): Triple<File?, File?, Long> = coroutineScope {
        val out = File(cacheDir, "out_${System.nanoTime()}.mp4")
        val passlogPrefix = File(cacheDir, "ffpass_${System.nanoTime()}").absolutePath

        val totalBudget = (limitBytes * 8.0 / duration / 1000.0 * 0.95).toInt()
        val (audioKbps, audioMono) = if (hasAudio) pickAudioKbps(totalBudget) else 0 to false
        val videoKbps0 = (totalBudget - audioKbps).coerceAtLeast(8)

        val vfArgs: List<String> = when {
            step.scaleFilter == null -> emptyList()
            step.scaleFilter.contains("fps=") -> listOf("-vf", step.scaleFilter)
            else -> listOf("-vf", "scale=${step.scaleFilter}")
        }
        val audioArgs: List<String> = if (hasAudio) {
            val ch = if (audioMono) listOf("-ac", "1") else emptyList()
            listOf("-c:a", "aac", "-b:a", "${audioKbps}k") + ch
        } else listOf("-an")

        val backoff = listOf(1.0, 0.85, 0.70, 0.55, 0.40)
        var winner: File? = null
        var effortPath: File? = null
        var effortSize = Long.MAX_VALUE

        try {
            for ((attempt, factor) in backoff.withIndex()) {
                coroutineContext.ensureActive()
                val kbps = (videoKbps0 * factor).toInt().coerceAtLeast(8)
                val stepFrac = stepIdx.toDouble() / totalSteps
                val nextFrac = (stepIdx + 1).toDouble() / totalSteps
                val pctBase = ((stepFrac + (attempt.toDouble() / backoff.size) * (nextFrac - stepFrac)) * 90).toInt()

                val scanMsg = if (attempt == 0)
                    "Scanning video (${step.label})…"
                else
                    "Adjusting — re-scanning (${step.label})…"
                onProgress(pctBase, scanMsg)

                cleanPasslog(passlogPrefix)

                val rc1 = ffmpeg.run(
                    listOf(
                        "-y", "-i", src.absolutePath,
                        *vfArgs.toTypedArray(),
                        "-c:v", "libx264", "-b:v", "${kbps}k",
                        "-preset", "medium",
                        "-pass", "1", "-passlogfile", passlogPrefix,
                        "-an", "-f", "null", "/dev/null",
                    )
                )
                if (rc1 != 0) continue

                onProgress(
                    pctBase + ((nextFrac - stepFrac) * 90 / backoff.size / 2).toInt().coerceAtLeast(1),
                    if (attempt == 0) "Encoding at ${kbps} kbps (${step.label})…"
                    else "Re-encoding at ${kbps} kbps (${step.label})…"
                )

                if (out.exists()) out.delete()
                val rc2 = ffmpeg.run(
                    listOf(
                        "-y", "-i", src.absolutePath,
                        *vfArgs.toTypedArray(),
                        "-c:v", "libx264", "-b:v", "${kbps}k",
                        "-preset", "medium",
                        "-pass", "2", "-passlogfile", passlogPrefix,
                        "-pix_fmt", "yuv420p",
                        "-movflags", "+faststart",
                        "-map_metadata", "-1",
                        "-threads", "0",
                        *audioArgs.toTypedArray(),
                        out.absolutePath,
                    )
                )
                if (rc2 != 0) continue
                if (!out.exists() || out.length() < 512) continue

                val size = out.length()
                if (size < effortSize) {
                    effortPath = out
                    effortSize = size
                }
                if (size <= limitBytes) {
                    winner = out
                    break
                }
            }
        } finally {
            cleanPasslog(passlogPrefix)
        }
        Triple(winner, effortPath, effortSize)
    }

    private fun cleanPasslog(prefix: String) {
        val parent = File(prefix).parentFile ?: return
        val basename = File(prefix).name
        parent.listFiles()?.forEach { f ->
            if (f.name.startsWith(basename)) f.delete()
        }
    }

    private suspend fun videoFallback(
        src: File,
        limitBytes: Long,
        cacheDir: File,
        hasAudio: Boolean,
        onProgress: OnProgress,
    ): CompressOutcome = coroutineScope {
        val out = File(cacheDir, "out_${System.nanoTime()}.mp4")
        val audioArgs = if (hasAudio) listOf("-c:a", "aac", "-b:a", "128k") else listOf("-an")
        for (crf in listOf(28, 35, 42, 51)) {
            coroutineContext.ensureActive()
            onProgress((crf * 85 / 51).coerceAtMost(85), "Video: CRF $crf (unknown duration)")
            if (out.exists()) out.delete()
            ffmpeg.run(
                listOf(
                    "-y", "-i", src.absolutePath,
                    "-c:v", "libx264", "-crf", crf.toString(), "-preset", "medium",
                    "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                    "-map_metadata", "-1",
                    *audioArgs.toTypedArray(),
                    out.absolutePath,
                )
            )
            if (out.exists() && out.length() in 1..limitBytes) {
                onProgress(100, "Video compressed")
                return@coroutineScope CompressOutcome(out, out.length(), true, "mp4")
            }
        }
        val size = if (out.exists()) out.length() else 0L
        CompressOutcome(out, size, false, "mp4")
    }
}
