package com.jakob.dmd.domain.model

import android.net.Uri

sealed interface JobStatus {
    data object Queued : JobStatus
    data class Working(val pct: Int, val message: String) : JobStatus
    data class Done(
        val outUri: Uri,
        val outBytes: Long,
        val originalBytes: Long,
        val metLimit: Boolean,
    ) : JobStatus
    data class Failed(val reason: String) : JobStatus
    data object Cancelled : JobStatus
}
