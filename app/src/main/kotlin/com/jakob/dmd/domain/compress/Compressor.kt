package com.jakob.dmd.domain.compress

import java.io.File

/** Per-attempt progress callback: pct in [0,100], short status string. */
typealias OnProgress = (Int, String) -> Unit

interface Compressor {
    /**
     * Compress [src] to fit within [limitBytes].
     *
     * Returns a [Result] with:
     *  - success(file) when a result was produced (which may be best-effort if [metLimit] is false)
     *
     * Use [CompressOutcome] returned via the [onComplete] callback to learn whether
     * the limit was actually met. Throws on irrecoverable failure or cancellation.
     */
    suspend fun compress(
        src: File,
        srcDisplayName: String,
        limitBytes: Long,
        cacheDir: File,
        onProgress: OnProgress,
    ): CompressOutcome
}

data class CompressOutcome(
    val outFile: File,
    val outBytes: Long,
    val metLimit: Boolean,
    /** Final file extension *without* the leading dot, e.g. "mp4". */
    val outExt: String,
)
