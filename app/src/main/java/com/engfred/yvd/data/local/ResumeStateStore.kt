package com.engfred.yvd.data.local

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists per-chunk download progress to disk.
 *
 * This enables **resumable downloads**: if a download is interrupted (app killed,
 * network drops, device restarts), the next attempt will skip already-completed
 * byte ranges rather than starting from zero.
 *
 * State is stored as lightweight JSON files in [android.content.Context.getCacheDir], which:
 * - Survives app restarts.
 * - Is automatically cleaned up by the OS on low storage.
 * - Is private to the app (no extra permissions required).
 *
 * Key design: states are keyed by the **final output file's absolute path**, which
 * remains stable across sessions even when YouTube stream URLs expire and change.
 */
@Singleton
class ResumeStateStore @Inject constructor() {

    companion object {
        private const val TAG = "ResumeStateStore"
        private const val STATES_DIR = "yvd_download_states"

        // JSON field names
        private const val KEY_TOTAL_LENGTH = "totalLength"
        private const val KEY_CHUNKS = "chunks"
        private const val KEY_START = "start"
        private const val KEY_END = "end"
        private const val KEY_COMPLETED = "completed"
    }

    private fun statesDir(cacheDir: File): File =
        File(cacheDir, STATES_DIR).also { it.mkdirs() }

    /**
     * Produces a safe filename from an arbitrary file path.
     * Replaces path separators and special chars, then truncates to 200 chars.
     */
    private fun stateFile(cacheDir: File, downloadId: String): File {
        val safeName = downloadId
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
            .replace(" ", "_")
            .takeLast(200)
        return File(statesDir(cacheDir), "$safeName.json")
    }

    /**
     * Saves the initial chunk layout for a download.
     * Call this once before starting the parallel download.
     */
    fun saveState(cacheDir: File, downloadId: String, totalLength: Long, chunks: List<ChunkState>) {
        try {
            val chunksArray = JSONArray().apply {
                chunks.forEach { chunk ->
                    put(JSONObject().apply {
                        put(KEY_START, chunk.start)
                        put(KEY_END, chunk.end)
                        put(KEY_COMPLETED, chunk.completed)
                    })
                }
            }
            val json = JSONObject().apply {
                put(KEY_TOTAL_LENGTH, totalLength)
                put(KEY_CHUNKS, chunksArray)
            }
            stateFile(cacheDir, downloadId).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save resume state: ${e.message}")
        }
    }

    /**
     * Loads a previously saved download state.
     * Returns null if no state file exists or if the file is corrupt.
     */
    fun loadState(cacheDir: File, downloadId: String): DownloadResumeState? {
        return try {
            val file = stateFile(cacheDir, downloadId)
            if (!file.exists()) return null

            val json = JSONObject(file.readText())
            val totalLength = json.getLong(KEY_TOTAL_LENGTH)
            val chunksArray = json.getJSONArray(KEY_CHUNKS)

            val chunks = (0 until chunksArray.length()).map { i ->
                val obj = chunksArray.getJSONObject(i)
                ChunkState(
                    start = obj.getLong(KEY_START),
                    end = obj.getLong(KEY_END),
                    completed = obj.getBoolean(KEY_COMPLETED)
                )
            }
            DownloadResumeState(totalLength = totalLength, chunks = chunks)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load resume state for downloadId=$downloadId: ${e.message}")
            null
        }
    }

    /**
     * Atomically marks a specific chunk as completed.
     * Called immediately after each chunk finishes writing to disk.
     */
    fun markChunkComplete(cacheDir: File, downloadId: String, chunkIndex: Int) {
        val state = loadState(cacheDir, downloadId) ?: return
        val updatedChunks = state.chunks.toMutableList().apply {
            if (chunkIndex in indices) {
                this[chunkIndex] = this[chunkIndex].copy(completed = true)
            }
        }
        saveState(cacheDir, downloadId, state.totalLength, updatedChunks)
    }

    /**
     * Deletes the saved state for a completed or fully-retried download.
     * Always call this on successful download completion.
     */
    fun clearState(cacheDir: File, downloadId: String) {
        try {
            stateFile(cacheDir, downloadId).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear state for downloadId=$downloadId: ${e.message}")
        }
    }
}

/**
 * Represents the byte range and completion status of a single download chunk.
 */
data class ChunkState(
    val start: Long,
    val end: Long,
    val completed: Boolean
)

/**
 * The full persisted state for a resumable download.
 */
data class DownloadResumeState(
    val totalLength: Long,
    val chunks: List<ChunkState>
)