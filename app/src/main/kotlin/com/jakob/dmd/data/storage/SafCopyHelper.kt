package com.jakob.dmd.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jakob.dmd.util.MediaTypeDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SourceMeta(
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
)

/**
 * Copies content:// URIs into the app's cacheDir so ffmpeg-kit (and our two-pass
 * passlog logic) can work with regular file paths. Avoids ffmpeg-kit's brittle
 * SAF-parameter helper and the two-pass + content:// gotchas called out in the plan.
 */
@Singleton
class SafCopyHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun queryMeta(uri: Uri): SourceMeta {
        var name = "input"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        val mime = context.contentResolver.getType(uri)
        return SourceMeta(name, size, mime)
    }

    fun copyToCache(uri: Uri, displayName: String): File {
        val ext = MediaTypeDetector.extOf(displayName).ifEmpty { "bin" }
        val dest = File(context.cacheDir, "in_${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open URI: $uri" }
            dest.outputStream().use { out -> input.copyTo(out, bufferSize = 64 * 1024) }
        }
        return dest
    }

    fun newCacheOutput(extWithDot: String): File =
        File(context.cacheDir, "out_${UUID.randomUUID()}$extWithDot")

    fun cleanupOld(prefix: String = "in_", olderThanMs: Long = 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix) && f.lastModified() < cutoff) {
                f.delete()
            }
        }
    }
}
