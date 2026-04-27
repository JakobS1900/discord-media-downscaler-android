package com.jakob.dmd.domain.ffmpeg

import android.media.MediaMetadataRetriever
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ProbeInfo(
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
)

/**
 * Replaces compressor.py's regex-on-stderr probe_media() with the proper Android API.
 * MediaMetadataRetriever is available on every device and gives us structured data.
 */
@Singleton
class MediaProbe @Inject constructor() {
    fun probe(file: File): ProbeInfo {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            return ProbeInfo(
                durationSeconds = durMs / 1000.0,
                width = w,
                height = h,
                hasAudio = hasAudio,
            )
        } catch (_: Exception) {
            return ProbeInfo(0.0, 0, 0, false)
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }
}
