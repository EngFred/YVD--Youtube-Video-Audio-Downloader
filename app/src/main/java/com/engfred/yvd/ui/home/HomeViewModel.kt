package com.engfred.yvd.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.engfred.yvd.common.Resource
import com.engfred.yvd.domain.model.AppTheme
import com.engfred.yvd.domain.model.VideoMetadata
import com.engfred.yvd.domain.repository.ThemeRepository
import com.engfred.yvd.domain.repository.YoutubeRepository
import com.engfred.yvd.util.MediaHelper
import com.engfred.yvd.util.UrlValidator
import com.engfred.yvd.worker.DownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * UI state for [HomeScreen].
 *
 * Kept as a flat data class (no sealed class hierarchy) so individual fields can be updated
 * selectively with [MutableStateFlow.update] — avoiding full-state rebuilds on every keystroke.
 */
data class HomeState(
    // URL input
    val urlInput: String = "",
    val urlError: String? = null,          // Shown inline beneath the text field

    // Metadata loading
    val isLoading: Boolean = false,
    val videoMetadata: VideoMetadata? = null,

    // Download progress
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadStatusText: String = "",

    // Terminal states
    val downloadComplete: Boolean = false,
    val downloadFailed: Boolean = false,
    val downloadedFile: File? = null,
    val isAudio: Boolean = false,

    // Error snackbar
    val error: String? = null,

    // Queue count — number of active or enqueued download workers
    val activeDownloadCount: Int = 0,

    // Dialog visibility
    val isFormatDialogVisible: Boolean = false,
    val isCancelDialogVisible: Boolean = false,
    val isThemeDialogVisible: Boolean = false,
) {
    /** True only when a download is in a terminal failed state and can be retried. */
    val canRetry: Boolean get() = downloadFailed && !isDownloading
}

/**
 * Stores the parameters needed to restart a failed download without asking the user again.
 */
private data class LastDownloadParams(
    val url: String,
    val formatId: String,
    val title: String,
    val isAudio: Boolean
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: YoutubeRepository,
    private val mediaHelper: MediaHelper,
    private val themeRepository: ThemeRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    val currentTheme = themeRepository.theme.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppTheme.SYSTEM
    )

    // Retained so we can cancel the specific job and so retry works.
    private var currentWorkId: UUID? = null
    private var lastDownloadParams: LastDownloadParams? = null

    init {
        observeGlobalQueueCount()
    }

    // ─── URL Input ────────────────────────────────────────────────────────────

    fun onUrlInputChanged(newUrl: String) {
        _state.update {
            it.copy(
                urlInput = newUrl,
                urlError = null,
                // Clear video card and download state when the user edits the URL.
                videoMetadata = if (newUrl.isBlank()) null else it.videoMetadata,
                downloadComplete = false,
                downloadFailed = false,
                downloadedFile = null,
                isDownloading = false,
                downloadProgress = 0f,
                error = null
            )
        }
    }

    // ─── Metadata Loading ─────────────────────────────────────────────────────

    /**
     * Validates and sanitizes [url], then fetches metadata via the repository.
     *
     * URL validation is done eagerly here so we surface a clear inline error message
     * ("Please paste a valid YouTube link") rather than a confusing NewPipe exception.
     */
    fun loadVideoInfo(url: String) {
        val sanitized = UrlValidator.sanitize(url)

        // Update the text field to the sanitized version so the user sees what will be used.
        if (sanitized != _state.value.urlInput) {
            _state.update { it.copy(urlInput = sanitized) }
        }

        if (!UrlValidator.isValidYouTubeUrl(sanitized)) {
            _state.update {
                it.copy(urlError = "Please paste a valid YouTube link (youtube.com or youtu.be)")
            }
            return
        }

        _state.update {
            it.copy(
                isLoading = true,
                urlError = null,
                error = null,
                videoMetadata = null,
                downloadComplete = false,
                downloadFailed = false
            )
        }

        repository.getVideoMetadata(sanitized)
            .onEach { result ->
                when (result) {
                    is Resource.Loading -> _state.update { it.copy(isLoading = true) }
                    is Resource.Success -> _state.update {
                        it.copy(isLoading = false, videoMetadata = result.data)
                    }
                    is Resource.Error -> _state.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    // ─── Download ─────────────────────────────────────────────────────────────

    /**
     * Enqueues a WorkManager download job with [NetworkType.CONNECTED] constraints.
     *
     * The job is tagged "download_job" so it can be counted (for the queue badge) and
     * cancelled in bulk (e.g. from the CancelReceiver broadcast).
     */
    fun downloadMedia(formatId: String, isAudio: Boolean) {
        val currentState = _state.value
        val url = currentState.urlInput
        val title = currentState.videoMetadata?.title ?: "video"

        // Cache the params so retry can restart without showing the format sheet again.
        lastDownloadParams = LastDownloadParams(url, formatId, title, isAudio)

        _state.update {
            it.copy(
                isDownloading = true,
                downloadProgress = 0f,
                downloadStatusText = "Queued…",
                downloadComplete = false,
                downloadFailed = false,
                downloadedFile = null,
                error = null,
                isAudio = isAudio,
                isFormatDialogVisible = false
            )
        }

        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    "url" to url,
                    "formatId" to formatId,
                    "title" to title,
                    "isAudio" to isAudio
                )
            )
            .addTag(TAG_DOWNLOAD_JOB)
            .build()

        currentWorkId = downloadRequest.id
        workManager.enqueue(downloadRequest)
        observeWork(downloadRequest.id)
    }

    /**
     * Re-runs the last download with identical parameters.
     * A no-op if there is no [lastDownloadParams] (shouldn't happen in practice since
     * the retry button is only shown when [HomeState.canRetry] is true).
     */
    fun retryDownload() {
        val params = lastDownloadParams ?: return
        downloadMedia(params.formatId, params.isAudio)
    }

    fun cancelDownload() {
        currentWorkId?.let { workManager.cancelWorkById(it) }
        _state.update {
            it.copy(
                isDownloading = false,
                downloadStatusText = "Cancelled",
                isCancelDialogVisible = false
            )
        }
    }

    // ─── Work Observation ─────────────────────────────────────────────────────

    private fun observeWork(id: UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                when (info.state) {
                    WorkInfo.State.ENQUEUED -> _state.update {
                        it.copy(isDownloading = true, downloadStatusText = "Waiting for network…")
                    }
                    WorkInfo.State.RUNNING -> {
                        val progress = info.progress.getFloat("progress", 0f)
                        val status = info.progress.getString("status") ?: "Downloading…"
                        _state.update {
                            it.copy(
                                isDownloading = true,
                                downloadProgress = progress,
                                downloadStatusText = status
                            )
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val path = info.outputData.getString("filePath")
                        val file = path?.let { File(it) }
                        _state.update {
                            it.copy(
                                isDownloading = false,
                                downloadComplete = true,
                                downloadFailed = false,
                                downloadProgress = 100f,
                                downloadedFile = file,
                                downloadStatusText = "Download complete"
                            )
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val errorMsg = info.outputData.getString("error")
                            ?: "Download failed — tap Retry to try again"
                        _state.update {
                            it.copy(
                                isDownloading = false,
                                downloadFailed = true,
                                downloadComplete = false,
                                downloadStatusText = errorMsg,
                                downloadProgress = 0f
                            )
                        }
                    }
                    WorkInfo.State.CANCELLED -> _state.update {
                        it.copy(
                            isDownloading = false,
                            downloadFailed = false,
                            downloadStatusText = "Download cancelled"
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Observes ALL "download_job" tagged work to keep [HomeState.activeDownloadCount] accurate.
     * This count drives the badge shown on the Download FAB / nav bar.
     */
    private fun observeGlobalQueueCount() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(TAG_DOWNLOAD_JOB).collect { infos ->
                val active = infos.count {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                _state.update { it.copy(activeDownloadCount = active) }
            }
        }
    }

    // ─── Media Actions ────────────────────────────────────────────────────────

    fun openMediaFile() {
        val file = _state.value.downloadedFile ?: return
        try {
            mediaHelper.openMediaFile(file)
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun shareMediaFile() {
        val file = _state.value.downloadedFile ?: return
        try {
            mediaHelper.shareMediaFile(file)
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    // ─── Dialog Visibility ────────────────────────────────────────────────────

    fun showFormatDialog() {
        if (_state.value.videoMetadata != null) {
            _state.update { it.copy(isFormatDialogVisible = true) }
        }
    }

    fun hideFormatDialog() = _state.update { it.copy(isFormatDialogVisible = false) }

    fun showCancelDialog() {
        if (_state.value.isDownloading) {
            _state.update { it.copy(isCancelDialogVisible = true) }
        }
    }

    fun hideCancelDialog() = _state.update { it.copy(isCancelDialogVisible = false) }

    fun showThemeDialog() = _state.update { it.copy(isThemeDialogVisible = true) }

    fun hideThemeDialog() = _state.update { it.copy(isThemeDialogVisible = false) }

    fun updateTheme(newTheme: AppTheme) {
        viewModelScope.launch {
            themeRepository.setTheme(newTheme)
            hideThemeDialog()
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        const val TAG_DOWNLOAD_JOB = "download_job"
    }
}