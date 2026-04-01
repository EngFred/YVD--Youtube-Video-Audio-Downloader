package com.engfred.yvd.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles opening, sharing, and thumbnail/artwork extraction for media files.
 *
 * ### Cache design
 * Two separate [LruCache] instances are used because they hold different data types
 * and need different size-of metrics:
 *
 * - **[bitmapCache]** — video thumbnails ([Bitmap]). Sized in KB via [Bitmap.byteCount].
 *   Uses 1/8 of the JVM max heap so the cache scales automatically with device RAM.
 *
 * - **[artworkCache]** — audio album art ([ByteArray]). Sized in bytes.
 *   Hard-capped at 4 MB. Audio artworks are usually ≤ 200 KB each, so this holds ~20 covers.
 *
 * Both caches are keyed by the file's absolute path, which is stable across sessions.
 * Callers should invoke [clearCaches] from [android.content.ComponentCallbacks2.onTrimMemory]
 * at the `TRIM_MEMORY_UI_HIDDEN` threshold or higher to free memory on background app kill.
 */
@Singleton
class MediaHelper @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    // ─── File Actions ─────────────────────────────────────────────────────────

    /**
     * Opens [file] in a compatible system app (video player or audio player).
     * Throws a descriptive exception if the file is missing or no handler is found.
     */
    fun openMediaFile(file: File) {
        if (!file.exists()) throw IllegalStateException("File not found: ${file.name}")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.resolveMimeType())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            throw IllegalStateException("No app found to open this file type (${file.extension})")
        }
        context.startActivity(intent)
    }

    /**
     * Opens the system share sheet for [file].
     * Throws a descriptive exception if the file is missing.
     */
    fun shareMediaFile(file: File) {
        if (!file.exists()) throw IllegalStateException("File not found: ${file.name}")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = file.resolveMimeType()
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, "Share Media").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    // ─── MIME helper ──────────────────────────────────────────────────────────

    private fun File.resolveMimeType(): String =
        when (extension.lowercase()) {
            "m4a", "mp3", "wav", "ogg", "aac", "flac", "opus" -> "audio/*"
            else -> "video/*"
        }

    // ─── Cache & Thumbnail Helpers ────────────────────────────────────────────

    companion object {

        /**
         * Memory-aware LRU cache for video frame thumbnails.
         *
         * Size is determined at class-load time from the JVM's reported max heap:
         *   - [Runtime.maxMemory] returns the heap limit the VM will never exceed.
         *   - We reserve 1/8 of that for thumbnail storage.
         *   - Each entry is charged [Bitmap.byteCount] bytes (actual pixel memory) rather
         *     than a flat 1-per-entry count, so large 4K-resolution thumbnails don't
         *     fill the cache without the LRU policy knowing about their true cost.
         */
        private val bitmapCache: LruCache<String, Bitmap> = run {
            val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            val cacheKb = maxKb / 8 // 1/8 of max heap in KB
            object : LruCache<String, Bitmap>(cacheKb) {
                override fun sizeOf(key: String, value: Bitmap): Int =
                    value.byteCount / 1024 // charge in KB
            }
        }

        /**
         * Memory-aware LRU cache for audio album artwork (raw JPEG/PNG bytes).
         *
         * Hard-capped at 4 MB — more than enough for ~20 album covers at 200 KB each.
         * Each entry is charged its byte length so large artworks count proportionally.
         */
        private val artworkCache: LruCache<String, ByteArray> = run {
            val maxBytes = 4 * 1024 * 1024 // 4 MB
            object : LruCache<String, ByteArray>(maxBytes) {
                override fun sizeOf(key: String, value: ByteArray): Int = value.size
            }
        }

        /**
         * Extracts embedded album art from an M4A (or other) audio file.
         *
         * - Returns the cached [ByteArray] immediately if present.
         * - Otherwise, reads from disk on a background IO thread, caches the result,
         *   and returns it. Returns `null` if no embedded art is found or extraction fails.
         */
        suspend fun getAudioArtwork(file: File): ByteArray? = withContext(Dispatchers.IO) {
            val key = file.absolutePath
            artworkCache.get(key)?.let { return@withContext it }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val art = retriever.embeddedPicture
                if (art != null) artworkCache.put(key, art)
                art
            } catch (e: Exception) {
                // Extraction failure is non-fatal — UI falls back to a placeholder icon.
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        /**
         * Extracts a representative thumbnail frame from a video file.
         *
         * - Returns the cached [Bitmap] immediately if present.
         * - Otherwise, generates the thumbnail on a background IO thread, caches it, and
         *   returns it. Returns `null` if generation fails (e.g. unsupported codec).
         */
        suspend fun getVideoThumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
            val key = file.absolutePath
            bitmapCache.get(key)?.let { return@withContext it }

            try {
                @Suppress("DEPRECATION") // MINI_KIND is still the best fit for list thumbnails.
                val bitmap = ThumbnailUtils.createVideoThumbnail(
                    file.absolutePath,
                    MediaStore.Video.Thumbnails.MINI_KIND
                )
                if (bitmap != null) bitmapCache.put(key, bitmap)
                bitmap
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Evicts all cached thumbnails and album art.
         *
         * Call this from your Application's [onTrimMemory] at the
         * [android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] threshold or above.
         */
        fun clearCaches() {
            bitmapCache.evictAll()
            artworkCache.evictAll()
        }
    }
}