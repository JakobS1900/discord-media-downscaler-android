package com.jakob.dmd.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jakob.dmd.data.storage.SafCopyHelper
import com.jakob.dmd.data.work.CompressionWorker
import com.jakob.dmd.domain.model.JobStatus
import com.jakob.dmd.domain.model.MediaItem
import com.jakob.dmd.domain.model.Tier
import com.jakob.dmd.util.MediaTypeDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val items: List<MediaItem> = emptyList(),
    val statuses: Map<String, JobStatus> = emptyMap(),
    val tier: Tier = Tier.DEFAULT,
    val autoOpen: Boolean = true,
    val isCompressing: Boolean = false,
    val statusLine: String = "Ready. Add files and tap Compress!",
    val lastOutputUri: Uri? = null,
    val lastOutputMime: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    app: Application,
    private val safCopy: SafCopyHelper,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val workManager: WorkManager by lazy { WorkManager.getInstance(getApplication()) }

    fun setTier(t: Tier) = _state.update { it.copy(tier = t) }
    fun setAutoOpen(v: Boolean) = _state.update { it.copy(autoOpen = v) }

    fun addUris(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        val current = _state.value.items.associateBy { it.id }.toMutableMap()
        uris.forEach { uri ->
            val id = uri.toString()
            if (current.containsKey(id)) return@forEach
            val meta = safCopy.queryMeta(uri)
            val kind = MediaTypeDetector.kindOf(meta.displayName) ?: return@forEach
            current[id] = MediaItem(
                id = id,
                sourceUri = uri,
                displayName = meta.displayName,
                sizeBytes = meta.sizeBytes,
                mimeType = meta.mimeType,
                kind = kind,
            )
        }
        _state.update { st ->
            val list = current.values.toList()
            st.copy(
                items = list,
                statuses = list.associate { it.id to (st.statuses[it.id] ?: JobStatus.Queued) },
                statusLine = "${list.size} file(s) queued.",
            )
        }
    }

    fun removeItem(id: String) = _state.update { st ->
        if (st.isCompressing) st else st.copy(
            items = st.items.filterNot { it.id == id },
            statuses = st.statuses - id,
        )
    }

    fun clear() = _state.update {
        if (it.isCompressing) it
        else it.copy(items = emptyList(), statuses = emptyMap(),
                     statusLine = "Ready. Add files and tap Compress!", lastOutputUri = null)
    }

    fun startCompression() {
        val s = _state.value
        if (s.isCompressing || s.items.isEmpty()) return
        _state.update { it.copy(
            isCompressing = true,
            statusLine = "Starting…",
            statuses = it.items.associate { mi -> mi.id to JobStatus.Queued },
        ) }
        // Cancel any existing tagged work, then enqueue all.
        workManager.cancelAllWorkByTag(CompressionWorker.TAG)

        viewModelScope.launch {
            for (item in s.items) {
                val data: Data = workDataOf(
                    CompressionWorker.KEY_URI to item.sourceUri.toString(),
                    CompressionWorker.KEY_LIMIT to s.tier.limitBytes,
                    CompressionWorker.KEY_NAME to item.displayName,
                    CompressionWorker.KEY_ORIG_SIZE to item.sizeBytes,
                )
                val req = OneTimeWorkRequestBuilder<CompressionWorker>()
                    .setInputData(data)
                    .addTag(CompressionWorker.TAG)
                    .addTag(item.id)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                workManager.enqueueUniqueWork(
                    "dmd-${item.id}",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    req,
                )
                observeWork(item, req.id)
            }
        }
    }

    private fun observeWork(item: MediaItem, workId: java.util.UUID) = viewModelScope.launch {
        workManager.getWorkInfoByIdFlow(workId).collect { info: WorkInfo? ->
            if (info == null) return@collect
            when (info.state) {
                WorkInfo.State.RUNNING -> {
                    val pct = info.progress.getInt(CompressionWorker.KEY_PROG_PCT, 0)
                    val msg = info.progress.getString(CompressionWorker.KEY_PROG_MSG) ?: "Working…"
                    updateStatus(item.id, JobStatus.Working(pct, msg))
                    _state.update { it.copy(statusLine = "${item.displayName}: $msg") }
                }
                WorkInfo.State.SUCCEEDED -> {
                    val outStr = info.outputData.getString(CompressionWorker.KEY_OUT_URI)
                    val outBytes = info.outputData.getLong(CompressionWorker.KEY_OUT_SIZE, 0L)
                    val origBytes = info.outputData.getLong(CompressionWorker.KEY_ORIG_SIZE, 0L)
                    val met = info.outputData.getBoolean(CompressionWorker.KEY_MET_LIMIT, false)
                    val outUri = outStr?.let(Uri::parse)
                    if (outUri != null) {
                        updateStatus(item.id, JobStatus.Done(outUri, outBytes, origBytes, met))
                        _state.update {
                            it.copy(
                                lastOutputUri = outUri,
                                lastOutputMime = item.mimeType,
                            )
                        }
                    } else {
                        updateStatus(item.id, JobStatus.Failed("missing output uri"))
                    }
                    maybeAllDone()
                }
                WorkInfo.State.FAILED -> {
                    val reason = info.outputData.getString(CompressionWorker.KEY_ERROR) ?: "unknown"
                    updateStatus(item.id, JobStatus.Failed(reason))
                    maybeAllDone()
                }
                WorkInfo.State.CANCELLED -> {
                    updateStatus(item.id, JobStatus.Cancelled)
                    maybeAllDone()
                }
                else -> Unit
            }
        }
    }

    private fun updateStatus(id: String, status: JobStatus) {
        _state.update { it.copy(statuses = it.statuses + (id to status)) }
    }

    private fun maybeAllDone() {
        val s = _state.value
        val pending = s.statuses.values.any { it is JobStatus.Working || it is JobStatus.Queued }
        if (pending) return
        val done = s.statuses.values.count { it is JobStatus.Done && (it as JobStatus.Done).metLimit }
        val warn = s.statuses.values.count { it is JobStatus.Done && !(it as JobStatus.Done).metLimit }
        val err = s.statuses.values.count { it is JobStatus.Failed }
        val parts = buildList {
            if (done > 0) add("$done compressed")
            if (warn > 0) add("$warn best effort")
            if (err > 0) add("$err failed")
        }
        _state.update { it.copy(
            isCompressing = false,
            statusLine = (parts.joinToString(", ").ifEmpty { "Done" }) +
                " — tap a row to open the result.",
        ) }
    }

    fun cancel() {
        workManager.cancelAllWorkByTag(CompressionWorker.TAG)
        _state.update { it.copy(isCompressing = false, statusLine = "Cancelled.") }
    }

    fun openIntentFor(uri: Uri, mime: String?): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
