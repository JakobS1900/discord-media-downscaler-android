package com.jakob.dmd.util

import java.util.Locale

object SizeFormat {
    fun format(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        n < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024.0 * 1024.0))
    }
}

object MediaTypeDetector {
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp")
    private val GIF = setOf("gif")
    private val VIDEO = setOf("mp4", "mov", "webm", "mkv", "avi")
    private val AUDIO = setOf("mp3", "ogg", "wav", "flac", "aac", "m4a")

    fun extOf(name: String): String =
        name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)

    fun isSupported(name: String): Boolean {
        val e = extOf(name)
        return e in IMAGE || e in GIF || e in VIDEO || e in AUDIO
    }

    fun kindOf(name: String): com.jakob.dmd.domain.model.MediaKind? = when (extOf(name)) {
        in IMAGE -> com.jakob.dmd.domain.model.MediaKind.IMAGE
        in GIF -> com.jakob.dmd.domain.model.MediaKind.GIF
        in VIDEO -> com.jakob.dmd.domain.model.MediaKind.VIDEO
        in AUDIO -> com.jakob.dmd.domain.model.MediaKind.AUDIO
        else -> null
    }

    fun mimeFor(ext: String): String = when (ext.lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "mov" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "aac", "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }
}
