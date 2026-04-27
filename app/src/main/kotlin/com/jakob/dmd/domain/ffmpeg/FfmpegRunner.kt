package com.jakob.dmd.domain.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Thin suspend wrapper around FFmpegKit.
 *
 * - Cancellable: cancelling the calling coroutine cancels the underlying FFmpeg session.
 * - Returns the ReturnCode value (0 == success, 255 == cancelled, anything else == failure).
 *
 * Logs are *not* surfaced here on purpose; the compressors only care about whether a
 * file came out the right size. If you need stderr later, attach a LogCallback in
 * FFmpegKitConfig at app startup.
 */
@Singleton
class FfmpegRunner @Inject constructor() {
    suspend fun run(args: List<String>): Int = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { s ->
            val rc = s.returnCode
            cont.resume(rc?.value ?: ReturnCode.SUCCESS)
        }
        cont.invokeOnCancellation {
            FFmpegKit.cancel(session.sessionId)
        }
    }

    suspend fun runOk(args: List<String>): Boolean = ReturnCode.isSuccess(
        com.arthenica.ffmpegkit.ReturnCode(run(args))
    )
}
