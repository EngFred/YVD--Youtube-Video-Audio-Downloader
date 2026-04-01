package com.engfred.yvd.data.repository

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import com.engfred.yvd.common.Resource
import com.engfred.yvd.data.local.ChunkState
import com.engfred.yvd.data.local.ResumeStateStore
import com.engfred.yvd.data.network.DownloaderImpl
import com.engfred.yvd.domain.model.AudioFormat
import com.engfred.yvd.domain.model.DownloadStatus
import com.engfred.yvd.domain.model.VideoFormat
import com.engfred.yvd.domain.model.VideoMetadata
import com.engfred.yvd.domain.repository.YoutubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Production implementation of [YoutubeRepository].
 *
 * Key architecture decisions:
 * - [DownloaderImpl] is injected (singleton) — NewPipe.init() is called ONCE in YVDApplication.
 * - [ResumeStateStore] persists per-chunk progress so interrupted downloads can resume.
 * - Temp mux files live in [Context.cacheDir] — no WRITE_EXTERNAL_STORAGE permission needed
 *   on API 29+, and the OS cleans them up automatically on low storage.
 * - All video and audio streams are filtered to MP4/M4A only (WebM excluded by design).
 * - YouTube CDN bypass: appending `&range=start-end` to CDN URLs alongside the HTTP `Range`
 *   header bypasses YouTube's per-connection throttling on adaptive streams.
 */
class YoutubeRepositoryImpl @Inject constructor(
    private val context: Context,
    private val downloaderImpl: DownloaderImpl,
    private val resumeStateStore: ResumeStateStore
) : YoutubeRepository {

    private val TAG = "YVD_REPO"

    // Shares the same OkHttpClient (connection pool + cookies) as NewPipe metadata calls.
    private val downloadClient: OkHttpClient = downloaderImpl.getOkHttpClient()

    // ─── Metadata ─────────────────────────────────────────────────────────────

    override fun getVideoMetadata(url: String): Flow<Resource<VideoMetadata>> = flow {
        Log.d(TAG, "Fetching metadata: $url")
        emit(Resource.Loading())
        try {
            val extractor = ServiceList.YouTube.getStreamExtractor(url) as YoutubeStreamExtractor
            extractor.fetchPage()

            val bestThumbnail = extractor.thumbnails
                .maxByOrNull { it.width }?.url
                ?: extractor.thumbnails.firstOrNull()?.url
                ?: ""

            val durationSeconds = extractor.length
            val allVideoStreams = extractor.videoStreams + extractor.videoOnlyStreams

            // MP4 only (WebM excluded by design). Sort: resolution desc, then fps desc.
            // Deduplicate on resolution+itag to remove identical entries NewPipe sometimes returns.
            val videoFormats = allVideoStreams
                .filter { it.format?.suffix == "mp4" }
                .sortedWith(
                    compareByDescending<VideoStream> {
                        it.resolution.replace(Regex("p.*"), "").toIntOrNull() ?: 0
                    }.thenByDescending {
                        it.resolution.substringAfter("p", "30")
                            .replace(Regex("\\D+"), "").toIntOrNull() ?: 30
                    }
                )
                .distinctBy { it.itag }
                .map { stream ->
                    VideoFormat(
                        formatId = stream.itag.toString(),
                        ext = stream.format?.suffix ?: "mp4",
                        resolution = stream.resolution,
                        fileSize = estimateFileSize(stream.bitrate.toLong(), durationSeconds),
                        fps = stream.resolution.substringAfter("p", "30")
                            .replace(Regex("\\D+"), "").toIntOrNull() ?: 30
                    )
                }

            // M4A only (Opus/WebM audio excluded by design). Deduplicate on bitrate.
            val audioFormats = extractor.audioStreams
                .filter { it.format?.suffix == "m4a" }
                .sortedByDescending { it.averageBitrate }
                .distinctBy { it.averageBitrate }
                .map { stream ->
                    AudioFormat(
                        formatId = stream.itag.toString(),
                        ext = stream.format?.suffix ?: "m4a",
                        bitrate = "${stream.averageBitrate}kbps",
                        fileSize = estimateFileSize(stream.bitrate.toLong(), durationSeconds)
                    )
                }

            emit(
                Resource.Success(
                    VideoMetadata(
                        id = extractor.url,
                        title = extractor.name,
                        thumbnailUrl = bestThumbnail,
                        duration = durationSeconds.toString(),
                        videoFormats = videoFormats,
                        audioFormats = audioFormats
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Metadata fetch failed: ${e.message}", e)
            emit(Resource.Error("Could not load video info: ${e.localizedMessage}"))
        }
    }.flowOn(Dispatchers.IO)

    // ─── Download Orchestration ───────────────────────────────────────────────

    override fun downloadVideo(
        url: String,
        formatId: String,
        title: String,
        isAudio: Boolean
    ): Flow<DownloadStatus> = callbackFlow {
        Log.d(TAG, "Download requested: '$title' | audio=$isAudio | format=$formatId")
        try {
            trySend(DownloadStatus.Progress(0f, "Initializing…"))

            // Always fetch fresh stream URLs — YouTube CDN URLs expire.
            val extractor = ServiceList.YouTube.getStreamExtractor(url) as YoutubeStreamExtractor
            extractor.fetchPage()

            val appDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "YVDownloader"
            ).also { if (!it.exists()) it.mkdirs() }

            // Safe filename: keep letters, digits, dots, hyphens, underscores and spaces → _
            val cleanTitle = title
                .replace(Regex("[^\\w.\\- ]"), "_")
                .trim()
                .replace(Regex("\\s+"), "_")
                .take(50)

            if (isAudio) {
                handleAudioDownload(extractor, formatId, cleanTitle, appDir)
            } else {
                handleVideoDownload(extractor, formatId, cleanTitle, appDir)
            }

            close()
        } catch (e: Exception) {
            Log.e(TAG, "Download pipeline failed: ${e.message}", e)
            trySend(DownloadStatus.Error(e.localizedMessage ?: "Unknown error"))
            close()
        }

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    // ─── Audio Download ───────────────────────────────────────────────────────

    private suspend fun ProducerScope<DownloadStatus>.handleAudioDownload(
        extractor: YoutubeStreamExtractor,
        formatId: String,
        cleanTitle: String,
        appDir: File
    ) {
        val stream = extractor.audioStreams.find { it.itag.toString() == formatId }
            ?: throw Exception("Audio stream not found (formatId=$formatId)")

        val ext = stream.format?.suffix ?: "m4a"
        val finalFile = File(appDir, "${cleanTitle}_${stream.averageBitrate}kbps.$ext")

        if (fileAlreadyDownloaded(finalFile)) {
            trySend(DownloadStatus.Progress(100f, "Already downloaded"))
            trySend(DownloadStatus.Success(finalFile))
            return
        }

        downloadStreamParallel(stream.content, finalFile, this, "Downloading audio…")
        trySend(DownloadStatus.Success(finalFile))
    }

    // ─── Video Download ───────────────────────────────────────────────────────

    private suspend fun ProducerScope<DownloadStatus>.handleVideoDownload(
        extractor: YoutubeStreamExtractor,
        formatId: String,
        cleanTitle: String,
        appDir: File
    ) {
        val allStreams = extractor.videoStreams + extractor.videoOnlyStreams
        val stream = allStreams.find { it.itag.toString() == formatId }
            ?: throw Exception("Video stream not found (formatId=$formatId)")

        val ext = stream.format?.suffix ?: "mp4"
        val finalFile = File(appDir, "${cleanTitle}_${stream.resolution}.$ext")

        if (fileAlreadyDownloaded(finalFile)) {
            trySend(DownloadStatus.Progress(100f, "Already downloaded"))
            trySend(DownloadStatus.Success(finalFile))
            return
        }

        if (!stream.isVideoOnly) {
            // Pre-merged stream (typically ≤ 360p). Single-track download.
            downloadStreamParallel(stream.content, finalFile, this, "Downloading video…")
            trySend(DownloadStatus.Success(finalFile))
        } else {
            // Adaptive stream (720p, 1080p+): separate video+audio, then mux.
            // Temp files go in cacheDir — OS-managed, no extra storage permission needed.
            val cacheDir = context.cacheDir
            val ts = System.currentTimeMillis()
            val videoTemp = File(cacheDir, "yvd_v_${ts}.$ext")
            val audioTemp = File(cacheDir, "yvd_a_${ts}.m4a")

            try {
                trySend(DownloadStatus.Progress(0f, "Downloading video track…"))
                downloadStreamParallel(stream.content, videoTemp, this, "Video track")

                val bestAudio = extractor.audioStreams
                    .filter { it.format?.suffix == "m4a" }
                    .maxByOrNull { it.averageBitrate }
                    ?: throw Exception("No M4A audio stream found to mux with video")

                trySend(DownloadStatus.Progress(0f, "Downloading audio track…"))
                downloadStreamParallel(bestAudio.content, audioTemp, this, "Audio track")

                trySend(DownloadStatus.Progress(0f, "Merging tracks…"))
                Log.d(TAG, "Muxing: video=${videoTemp.length()}B  audio=${audioTemp.length()}B")
                muxAudioVideo(
                    audioPath = audioTemp.absolutePath,
                    videoPath = videoTemp.absolutePath,
                    outPath = finalFile.absolutePath,
                    format = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                Log.d(TAG, "Mux complete → ${finalFile.name} (${finalFile.length()}B)")
                trySend(DownloadStatus.Success(finalFile))

            } finally {
                // Always clean up temp files and their resume states.
                videoTemp.delete()
                audioTemp.delete()
                resumeStateStore.clearState(cacheDir, videoTemp.absolutePath)
                resumeStateStore.clearState(cacheDir, audioTemp.absolutePath)
            }
        }
    }

    // ─── Parallel Download Engine ─────────────────────────────────────────────

    /**
     * Splits the file into [THREAD_COUNT] parallel chunks and downloads them simultaneously.
     *
     * Resume logic:
     * - Before starting, checks [ResumeStateStore] for a previous session matching this file path.
     * - Completed chunks are skipped; only pending chunks are fetched.
     * - Each chunk marks itself complete in the store immediately after writing.
     * - On successful completion the state file is deleted.
     * - On failure the state file is kept so the next call can resume from where it left off.
     *
     * Small files (< [SMALL_FILE_THRESHOLD]) skip the multi-thread overhead entirely.
     */
    private suspend fun downloadStreamParallel(
        streamUrl: String,
        file: File,
        flow: ProducerScope<DownloadStatus>,
        statusPrefix: String
    ) = withContext(Dispatchers.IO) {

        val downloadId = file.absolutePath
        var totalLength = -1L

        // HEAD request to get Content-Length (retry up to 3 times with backoff).
        for (attempt in 0..2) {
            try {
                val headReq = Request.Builder().url(streamUrl).head().build()
                downloadClient.newCall(headReq).execute().use { resp ->
                    totalLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
                if (totalLength > 0) break
            } catch (e: Exception) {
                Log.w(TAG, "HEAD attempt $attempt failed: ${e.message}")
                if (attempt < 2) delay(500L * (attempt + 1))
            }
        }

        Log.d(TAG, "Stream: ${file.name} | size=${totalLength}B")

        if (totalLength <= 0 || totalLength < SMALL_FILE_THRESHOLD) {
            Log.d(TAG, "Single-thread fallback (size unknown or < ${SMALL_FILE_THRESHOLD / 1024}KB)")
            downloadSingleThread(streamUrl, file, totalLength, flow, statusPrefix)
            return@withContext
        }

        // Check for a prior incomplete download that can be resumed.
        val savedState = resumeStateStore.loadState(context.cacheDir, downloadId)
        val chunks: List<ChunkState>

        if (savedState != null && savedState.totalLength == totalLength) {
            val doneCount = savedState.chunks.count { it.completed }
            Log.d(TAG, "Resuming: $doneCount/${savedState.chunks.size} chunks already done")
            chunks = savedState.chunks
            // Ensure the file is pre-allocated if it was lost between sessions.
            if (!file.exists() || file.length() != totalLength) {
                RandomAccessFile(file, "rw").use { it.setLength(totalLength) }
            }
        } else {
            // Fresh download — divide evenly across THREAD_COUNT chunks.
            val partSize = totalLength / THREAD_COUNT
            chunks = (0 until THREAD_COUNT).map { i ->
                ChunkState(
                    start = i * partSize,
                    end = if (i == THREAD_COUNT - 1) totalLength - 1 else (i + 1) * partSize - 1,
                    completed = false
                )
            }
            resumeStateStore.saveState(context.cacheDir, downloadId, totalLength, chunks)
            RandomAccessFile(file, "rw").use { it.setLength(totalLength) }
        }

        // Seed the atomic counter with bytes already downloaded from completed chunks.
        val downloadedBytes = AtomicLong(
            chunks.filter { it.completed }.sumOf { it.end - it.start + 1 }
        )

        // Emit initial progress from completed chunks (important for resume UX).
        if (downloadedBytes.get() > 0) {
            val pct = (downloadedBytes.get().toFloat() / totalLength * 100f).coerceIn(0f, 99f)
            flow.trySend(DownloadStatus.Progress(pct, "Resuming… ${pct.toInt()}%"))
        }

        try {
            coroutineScope {
                val jobs = chunks.mapIndexed { index, chunk ->
                    async(Dispatchers.IO) {
                        if (chunk.completed) {
                            Log.d(TAG, "Chunk $index skipped (already complete)")
                            return@async
                        }
                        retryWithBackoff(RETRY_COUNT) {
                            downloadChunk(
                                url = streamUrl,
                                file = file,
                                chunk = chunk,
                                chunkIndex = index,
                                downloadId = downloadId,
                                totalFileLength = totalLength,
                                atomicProgress = downloadedBytes,
                                flow = flow,
                                statusPrefix = statusPrefix
                            )
                        }
                    }
                }
                jobs.awaitAll()
            }

            Log.d(TAG, "Parallel download complete: ${file.name}")
            resumeStateStore.clearState(context.cacheDir, downloadId)

        } catch (e: Exception) {
            Log.e(TAG, "Parallel download failed — state preserved for resume: ${e.message}")
            // Do NOT delete the partial file here; the state file enables resuming.
            throw e
        }
    }

    // ─── Chunk Downloader ─────────────────────────────────────────────────────

    /**
     * Downloads one byte-range chunk and writes it to the correct offset in [file].
     *
     * YouTube CDN bypass: `&range=start-end` is appended to the URL. This is a YouTube-specific
     * server-side range parameter that tells the CDN to begin streaming from [chunk.start],
     * which bypasses the per-connection bandwidth throttling YouTube applies to adaptive streams.
     * The HTTP `Range` header is also sent for servers that honour it but not the URL param.
     */
    private fun downloadChunk(
        url: String,
        file: File,
        chunk: ChunkState,
        chunkIndex: Int,
        downloadId: String,
        totalFileLength: Long,
        atomicProgress: AtomicLong,
        flow: ProducerScope<DownloadStatus>,
        statusPrefix: String
    ) {
        val rangedUrl = appendYouTubeRangeParam(url, chunk.start, chunk.end)

        val request = Request.Builder()
            .url(rangedUrl)
            .header("Range", "bytes=${chunk.start}-${chunk.end}")
            .build()

        val raf = RandomAccessFile(file, "rw")
        raf.seek(chunk.start)

        try {
            val response = downloadClient.newCall(request).execute()
            // 200 OK is acceptable when the server ignores Range (returns full file).
            // 206 Partial Content is the correct response for a range request.
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Chunk $chunkIndex failed with HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty body for chunk $chunkIndex")
            val buffer = ByteArray(IO_BUFFER_SIZE)
            var bytesRead: Int

            body.byteStream().use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    raf.write(buffer, 0, bytesRead)
                    val total = atomicProgress.addAndGet(bytesRead.toLong())
                    // Throttle UI updates to ~every 256 KB to avoid flooding the main thread.
                    if (totalFileLength > 0 && total % PROGRESS_REPORT_EVERY_BYTES < bytesRead) {
                        val pct = (total.toFloat() / totalFileLength * 100f).coerceIn(0f, 100f)
                        flow.trySend(DownloadStatus.Progress(pct, "$statusPrefix ${pct.toInt()}%"))
                    }
                }
            }

            // Persist completion immediately so a crash here still preserves progress.
            resumeStateStore.markChunkComplete(context.cacheDir, downloadId, chunkIndex)
            Log.d(TAG, "Chunk $chunkIndex done [${chunk.start}–${chunk.end}]")

        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    // ─── Single-Thread Fallback ───────────────────────────────────────────────

    /**
     * Used for small files or when [totalLength] is unknown (server didn't send Content-Length).
     * Also used when Range headers are not supported by the server.
     */
    private fun downloadSingleThread(
        url: String,
        file: File,
        knownLength: Long,
        flow: ProducerScope<DownloadStatus>,
        statusPrefix: String
    ) {
        val request = Request.Builder().url(url).build()
        val response = downloadClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

        val body = response.body ?: throw IOException("Empty response body")
        val totalLength = if (knownLength > 0) knownLength else body.contentLength()

        val buffer = ByteArray(IO_BUFFER_SIZE)
        var copied = 0L
        var lastReportedProgress = -1

        FileOutputStream(file).use { out ->
            body.byteStream().use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    copied += read
                    if (totalLength > 0) {
                        val pct = (copied.toFloat() / totalLength * 100f).toInt()
                        if (pct >= lastReportedProgress + 2) {
                            lastReportedProgress = pct
                            flow.trySend(DownloadStatus.Progress(pct.toFloat(), "$statusPrefix $pct%"))
                        }
                    }
                }
            }
        }
    }

    // ─── Retry Helper ─────────────────────────────────────────────────────────

    /**
     * Runs [block] up to [times] times. Uses exponential backoff (1s, 2s, 3s…) between attempts.
     * Lets the final attempt throw so the caller can decide what to do.
     */
    private suspend fun retryWithBackoff(times: Int, block: () -> Unit) {
        repeat(times - 1) { attempt ->
            try {
                block(); return
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1}/$times failed: ${e.message}. Retrying…")
                delay(1_000L * (attempt + 1))
            }
        }
        block() // Final attempt — propagate any exception.
    }

    // ─── Muxing ───────────────────────────────────────────────────────────────

    /**
     * Merges separate video-only and audio-only MP4 tracks into a single output MP4.
     * Uses Android's native [MediaMuxer] — no external libraries needed.
     *
     * On failure, the corrupt output file is deleted and the exception is re-thrown.
     */
    private fun muxAudioVideo(audioPath: String, videoPath: String, outPath: String, format: Int) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(outPath, format)

        try {
            videoExtractor.setDataSource(videoPath)
            val vTrack = findTrackIndex(videoExtractor, "video/")
            videoExtractor.selectTrack(vTrack)
            val muxVTrack = muxer.addTrack(videoExtractor.getTrackFormat(vTrack))

            audioExtractor.setDataSource(audioPath)
            val aTrack = findTrackIndex(audioExtractor, "audio/")
            audioExtractor.selectTrack(aTrack)
            val muxATrack = muxer.addTrack(audioExtractor.getTrackFormat(aTrack))

            muxer.start()

            val buffer = ByteBuffer.allocate(MUX_BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()

            copyTrack(videoExtractor, muxer, muxVTrack, buffer, info)
            copyTrack(audioExtractor, muxer, muxATrack, buffer, info)

        } catch (e: Exception) {
            File(outPath).delete() // Don't leave a corrupt output file.
            Log.e(TAG, "Muxing failed: ${e.message}", e)
            throw e
        } finally {
            try { muxer.stop(); muxer.release() } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
        }
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size <= 0) break
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            info.size = size
            muxer.writeSampleData(trackIndex, buffer, info)
            extractor.advance()
        }
    }

    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith(mimePrefix) == true
            ) return i
        }
        throw IllegalArgumentException("No track with MIME prefix '$mimePrefix' found")
    }

    // ─── Utility Helpers ──────────────────────────────────────────────────────

    /**
     * Appends YouTube's server-side range parameter to CDN URLs.
     *
     * YouTube adaptive-stream CDN URLs (googlevideo.com) support `&range=start-end` as a
     * query parameter. When this is set, the CDN begins streaming from that byte offset
     * at full bandwidth — bypassing the throttling it applies to long-lived single connections.
     *
     * This is used IN ADDITION TO the HTTP `Range` header. On non-YouTube servers the
     * parameter is harmless (ignored), so this function is safe to call unconditionally.
     */
    private fun appendYouTubeRangeParam(url: String, start: Long, end: Long): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "${url}${separator}range=${start}-${end}"
    }

    /**
     * Returns true if the file exists and has a non-trivial size (> 1 KB).
     * We use 1 KB rather than 0 to guard against leftover zero-byte files from crashed sessions.
     */
    private fun fileAlreadyDownloaded(file: File): Boolean =
        file.exists() && file.length() > 1_024L

    /**
     * Estimates file size from bitrate × duration.
     * The actual size can differ; the HEAD Content-Length is authoritative during download.
     */
    private fun estimateFileSize(bitrateBps: Long, durationSec: Long): String {
        if (bitrateBps <= 0 || durationSec <= 0) return "Unknown"
        val bytes = (bitrateBps * durationSec) / 8
        return "%.1f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        /** Number of parallel download threads per file. */
        private const val THREAD_COUNT = 4

        /** Read/write I/O buffer per thread. 64 KB balances throughput vs GC pressure. */
        private const val IO_BUFFER_SIZE = 64 * 1024

        /** Muxer buffer — must fit the largest single video frame (2 MB covers 4K). */
        private const val MUX_BUFFER_SIZE = 2 * 1024 * 1024

        /** Files smaller than this are downloaded on a single thread. */
        private const val SMALL_FILE_THRESHOLD = 512 * 1024L // 512 KB

        /** Minimum bytes between consecutive UI progress updates per thread. */
        private const val PROGRESS_REPORT_EVERY_BYTES = 256 * 1024L // 256 KB

        /** How many times to retry a failed chunk before giving up. */
        private const val RETRY_COUNT = 3
    }
}