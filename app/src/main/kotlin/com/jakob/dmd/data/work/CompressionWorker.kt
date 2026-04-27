package com.jakob.dmd.data.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jakob.dmd.DmdApp
import com.jakob.dmd.MainActivity
import com.jakob.dmd.R
import com.jakob.dmd.data.storage.MediaStorePublisher
import com.jakob.dmd.data.storage.SafCopyHelper
import com.jakob.dmd.domain.compress.CompressorRouter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class CompressionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val safCopy: SafCopyHelper,
    private val router: CompressorRouter,
    private val publisher: MediaStorePublisher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uriStr = inputData.getString(KEY_URI) ?: return Result.failure(
            workDataOf(KEY_ERROR to "no uri"))
        val limitBytes = inputData.getLong(KEY_LIMIT, 10L * 1024 * 1024)
        val displayName = inputData.getString(KEY_NAME) ?: "input"
        val originalBytes = inputData.getLong(KEY_ORIG_SIZE, 0L)

        setForeground(makeForegroundInfo(displayName, "Starting…", 0))
        safCopy.cleanupOld()

        val uri = Uri.parse(uriStr)
        val srcCache = try {
            safCopy.copyToCache(uri, displayName)
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_ERROR to "copy failed: ${e.message}"))
        }

        try {
            val compressor = router.forName(displayName)
            val outcome = compressor.compress(
                src = srcCache,
                srcDisplayName = displayName,
                limitBytes = limitBytes,
                cacheDir = applicationContext.cacheDir,
            ) { pct, msg ->
                setProgressAsyncSafe(pct, msg)
                runCatching {
                    setForeground(makeForegroundInfo(displayName, msg, pct))
                }
            }

            val publishedUri = publisher.publish(outcome.outFile, displayName)
            outcome.outFile.delete()
            srcCache.delete()

            return Result.success(workDataOf(
                KEY_OUT_URI to publishedUri.toString(),
                KEY_OUT_SIZE to outcome.outBytes,
                KEY_ORIG_SIZE to originalBytes,
                KEY_MET_LIMIT to outcome.metLimit,
                KEY_NAME to displayName,
            ))
        } catch (ce: CancellationException) {
            srcCache.delete()
            throw ce
        } catch (e: Exception) {
            srcCache.delete()
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
        }
    }

    private fun setProgressAsyncSafe(pct: Int, msg: String) {
        runCatching {
            setProgressAsync(workDataOf(KEY_PROG_PCT to pct, KEY_PROG_MSG to msg))
        }
    }

    private fun makeForegroundInfo(name: String, msg: String, pct: Int): ForegroundInfo {
        val tap = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(applicationContext, DmdApp.NOTIF_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notif_compressing_title))
            .setContentText("$name — $msg")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceIn(0, 100), pct <= 0)
            .setContentIntent(tap)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_LIMIT = "limit"
        const val KEY_NAME = "name"
        const val KEY_ORIG_SIZE = "orig_size"

        const val KEY_PROG_PCT = "p_pct"
        const val KEY_PROG_MSG = "p_msg"

        const val KEY_OUT_URI = "out_uri"
        const val KEY_OUT_SIZE = "out_size"
        const val KEY_MET_LIMIT = "met_limit"
        const val KEY_ERROR = "error"

        const val TAG = "dmd-compression"
        private const val NOTIF_ID = 1001
    }
}
