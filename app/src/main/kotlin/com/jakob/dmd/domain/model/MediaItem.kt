package com.jakob.dmd.domain.model

import android.net.Uri

enum class MediaKind { IMAGE, VIDEO, AUDIO, GIF }

data class MediaItem(
    val id: String,            // stable id (URI string) used as Compose key
    val sourceUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val kind: MediaKind,
)
