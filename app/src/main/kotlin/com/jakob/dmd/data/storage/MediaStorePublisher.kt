package com.jakob.dmd.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jakob.dmd.util.MediaTypeDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes a finished file from cacheDir into the user-visible
 * Downloads/DiscordDownscaler/ folder via MediaStore. Returns a content:// URI
 * that other apps can open (e.g. Discord, Photos).
 *
 * On API 29+ no permissions are needed — MediaStore handles it.
 */
@Singleton
class MediaStorePublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val subDir = "DiscordDownscaler"

    fun publish(file: File, originalDisplayName: String): Uri {
        val ext = MediaTypeDetector.extOf(file.name).ifEmpty {
            MediaTypeDetector.extOf(originalDisplayName)
        }
        val mime = MediaTypeDetector.mimeFor(ext)
        val outName = buildOutName(originalDisplayName, ext)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(file, outName, mime)
        } else {
            publishViaDirectFile(file, outName)
        }
    }

    private fun publishViaMediaStore(file: File, outName: String, mime: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subDir")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) {
            "MediaStore.insert returned null"
        }
        try {
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { "Cannot open output stream for $uri" }
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
            val finish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, finish, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishViaDirectFile(file: File, outName: String): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            subDir,
        )
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, outName)
        file.inputStream().use { input ->
            target.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        return Uri.fromFile(target)
    }

    private fun buildOutName(original: String, ext: String): String {
        val stem = original.substringBeforeLast('.', missingDelimiterValue = original)
        return "${stem}_discord.$ext"
    }
}
