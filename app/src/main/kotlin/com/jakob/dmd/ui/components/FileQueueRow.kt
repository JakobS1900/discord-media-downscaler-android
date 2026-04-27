package com.jakob.dmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakob.dmd.domain.model.JobStatus
import com.jakob.dmd.domain.model.MediaItem
import com.jakob.dmd.domain.model.MediaKind
import com.jakob.dmd.ui.theme.DmdBg2
import com.jakob.dmd.ui.theme.DmdFg
import com.jakob.dmd.ui.theme.DmdFgDim
import com.jakob.dmd.ui.theme.DmdGreen
import com.jakob.dmd.ui.theme.DmdRed
import com.jakob.dmd.ui.theme.DmdYellow
import com.jakob.dmd.util.SizeFormat

@Composable
fun FileQueueRow(
    item: MediaItem,
    status: JobStatus,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DmdBg2)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = iconFor(item.kind),
            contentDescription = null,
            tint = DmdFgDim,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                color = DmdFg,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Text(
                text = sizeAndStatus(item, status),
                color = colorFor(status),
                fontSize = 11.sp,
                maxLines = 1,
                fontWeight = FontWeight.Medium,
            )
        }
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Text("✕", color = DmdFgDim)
            }
        }
    }
}

private fun iconFor(kind: MediaKind) = when (kind) {
    MediaKind.IMAGE, MediaKind.GIF -> Icons.Filled.Image
    MediaKind.VIDEO -> Icons.Filled.Movie
    MediaKind.AUDIO -> Icons.Filled.Audiotrack
}

private fun sizeAndStatus(item: MediaItem, status: JobStatus): String {
    val size = SizeFormat.format(item.sizeBytes)
    return when (status) {
        JobStatus.Queued -> "$size  •  Queued"
        is JobStatus.Working -> "$size  •  ${status.message}"
        is JobStatus.Done -> {
            val outSize = SizeFormat.format(status.outBytes)
            val savings = if (item.sizeBytes > 0)
                ((1.0 - status.outBytes.toDouble() / item.sizeBytes) * 100).toInt()
            else 0
            val tag = if (status.metLimit) "✓" else "⚠"
            "$size → $outSize  •  $tag ${if (savings > 0) "-${savings}%" else "no change"}"
        }
        is JobStatus.Failed -> "$size  •  ✗ ${status.reason.take(40)}"
        JobStatus.Cancelled -> "$size  •  Cancelled"
    }
}

private fun colorFor(status: JobStatus): Color = when (status) {
    JobStatus.Queued -> DmdFgDim
    is JobStatus.Working -> DmdYellow
    is JobStatus.Done -> if (status.metLimit) DmdGreen else DmdYellow
    is JobStatus.Failed -> DmdRed
    JobStatus.Cancelled -> DmdFgDim
}
