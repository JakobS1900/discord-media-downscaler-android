package com.jakob.dmd.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakob.dmd.domain.model.JobStatus
import com.jakob.dmd.ui.components.AnimatedProgressBar
import com.jakob.dmd.ui.components.FileQueueRow
import com.jakob.dmd.ui.components.TierSelector
import com.jakob.dmd.ui.theme.DmdAccent
import com.jakob.dmd.ui.theme.DmdBg
import com.jakob.dmd.ui.theme.DmdBg2
import com.jakob.dmd.ui.theme.DmdBg3
import com.jakob.dmd.ui.theme.DmdFg
import com.jakob.dmd.ui.theme.DmdFgDim
import com.jakob.dmd.viewmodel.HomeViewModel

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            vm.addUris(uris)
        }
    }

    LaunchedEffect(state.lastOutputUri, state.isCompressing) {
        val uri = state.lastOutputUri
        if (uri != null && !state.isCompressing && state.autoOpen) {
            runCatching { context.startActivity(vm.openIntentFor(uri, state.lastOutputMime)) }
        }
    }

    Scaffold(
        containerColor = DmdBg,
        contentColor = DmdFg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DmdBg)
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Discord Media Downscaler",
                color = DmdFg,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Compress anything to fit Discord — with minimal quality loss.",
                color = DmdFgDim,
                fontSize = 12.sp,
            )

            DropZone(
                onClick = {
                    pickFiles.launch(arrayOf("image/*", "video/*", "audio/*"))
                },
            )

            Text("Limit:", color = DmdFgDim, fontSize = 12.sp)
            TierSelector(selected = state.tier, onChange = { vm.setTier(it) })

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = state.items, key = { it.id }) { item ->
                    val status = state.statuses[item.id] ?: JobStatus.Queued
                    FileQueueRow(
                        item = item,
                        status = status,
                        canRemove = !state.isCompressing,
                        onTap = {
                            (status as? JobStatus.Done)?.let { d ->
                                runCatching { context.startActivity(vm.openIntentFor(d.outUri, item.mimeType)) }
                            }
                        },
                        onRemove = { vm.removeItem(item.id) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { pickFiles.launch(arrayOf("image/*", "video/*", "audio/*")) },
                    enabled = !state.isCompressing,
                ) { Text("+ Add", color = DmdFg) }
                OutlinedButton(
                    onClick = { vm.clear() },
                    enabled = !state.isCompressing && state.items.isNotEmpty(),
                ) { Text("Clear", color = DmdFg) }
                Spacer(Modifier.weight(1f))
                if (state.isCompressing) {
                    Button(
                        onClick = { vm.cancel() },
                        colors = ButtonDefaults.buttonColors(containerColor = DmdBg3),
                    ) { Text("Cancel", color = DmdFg) }
                } else {
                    Button(
                        onClick = { vm.startCompression() },
                        enabled = state.items.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = DmdAccent),
                    ) { Text("Compress!", color = DmdFg, fontWeight = FontWeight.Bold) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.autoOpen,
                    onCheckedChange = { vm.setAutoOpen(it) },
                    colors = CheckboxDefaults.colors(checkedColor = DmdAccent),
                )
                Text("Auto-open output when done", color = DmdFgDim, fontSize = 12.sp)
            }

            AnimatedProgressBar(
                progress = overallProgress(state.statuses.values.toList()),
            )
            Text(state.statusLine, color = DmdFgDim, fontSize = 12.sp, maxLines = 2)
        }
    }
}

@Composable
private fun DropZone(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DmdBg2)
            .border(2.dp, DmdBg3, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Tap here to add files\n(images, video, audio)",
            color = DmdFgDim,
            fontSize = 14.sp,
        )
    }
}

private fun overallProgress(statuses: List<JobStatus>): Float {
    if (statuses.isEmpty()) return 0f
    var sum = 0.0
    for (s in statuses) {
        sum += when (s) {
            JobStatus.Queued -> 0.0
            is JobStatus.Working -> s.pct / 100.0
            is JobStatus.Done -> 1.0
            is JobStatus.Failed -> 1.0
            JobStatus.Cancelled -> 1.0
        }
    }
    return (sum / statuses.size).toFloat().coerceIn(0f, 1f)
}
