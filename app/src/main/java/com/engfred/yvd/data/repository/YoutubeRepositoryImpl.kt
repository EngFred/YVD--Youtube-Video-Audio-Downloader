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
import com.engfred.yvd.domain.model.PlaylistMetadata
import com.engfred.yvd.domain.model.PlaylistVideoItem
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
 * ## Bug fixes — v1.1 (0:00 duration / invalid playlist track fixes)
 *
 * ### Fix 1 — single-thread partial file falsely passes isFileComplete()  [PRIMARY BUG]
 * `downloadSingleThread` never saves a ResumeState (by design — it is a best-effort fallback).
 * When a network drop interrupted it mid-download, the partial file remained on disk.  On the
 * next Retry, `isFileComplete()` saw:
 *   • file.exists() == true
 *   • file.length() > 1 024 (the partial M4A header + some audio data)
 *   • resumeStateStore.loadState() == null   ← no state was ever saved
 * → returned true → the corrupt partial file was served as "already downloaded" → 0:00 duration
 * because the moov atom at the end of the file was never written.
 *
 * Fix: `downloadSingleThread` now wraps all I/O in try/catch and **deletes the partial file on
 * any exception** before re-throwing.  A missing file always causes `isFileComplete()` to return
 * false, forcing a clean re-download on the next Retry.
 *
 * Additionally, if the total byte count received is less than the known Content-Length an
 * IOException is thrown (and the partial file is deleted) even when the stream ended without
 * a hard error — this catches the case where the server closes the connection early and the
 * read loop exits via -1 rather than by throwing.
 *
 * ### Fix 2 — parallel chunk silent short-read leaves zero-filled regions  [SECONDARY BUG]
 * `downloadChunk` pre-allocates the full output file via `RandomAccessFile.setLength()`.  If
 * YouTube's CDN returned a 200 response whose body was shorter than the requested byte range
 * (e.g. an error payload, or a connection that closed cleanly after a partial body), the read
 * loop exited without throwing.  The chunk was then marked complete, but the trailing
 * pre-allocated zeros remained in the file.  Zero-filled regions inside an M4A container
 * corrupt the sample table → MediaPlayer reads 0 duration.
 *
 * Fix: `downloadChunk` now counts every byte written and compares the total against
 * `chunk.end - chunk.start + 1`.  A mismatch throws `IOException`, which triggers the
 * existing `retryWithBackoff` logic.  The chunk is not marked complete until the full
 * expected byte count is confirmed.
 *
 * ### Fix 3 — stale resume state after single-thread fallback success  [TERTIARY BUG]
 * If a prior session started a parallel download (saving a ResumeState), was interrupted,
 * and the next session's HEAD request failed so it fell back to `downloadSingleThread` — on
 * success the stale ResumeState was never cleared.  Subsequent calls to `isFileComplete()`
 * on that file (e.g. if the worker ran again for any reason) returned false because of the
 * stale state, causing unnecessary re-downloads.
 *
 * Fix: `downloadStreamParallel` now calls `resumeStateStore.clearState()` immediately after
 * a successful `downloadSingleThread` call.
 *
 * ### Fix 4 — filename collision for playlist tracks with long identical title prefixes
 * `cleanTitle` was truncated to 50 characters.  Two playlist items whose titles share the
 * same first 50 characters (e.g. series episodes) produced the same output filename and
 * competed for the same file and ResumeState, corrupting each other's downloads.
 *
 * Fix: when the sanitised title exceeds 50 characters, the last 6 hex digits of the full
 * title's hash code are appended as a disambiguator, keeping filenames short while guaranteeing
 * uniqueness within any realistic playlist.
 */
class YoutubeRepositoryImpl @Inject constructor(
    private val context: Context,
    private val downloaderImpl: DownloaderImpl,
    private val resumeStateStore: ResumeStateStore
) : YoutubeRepository {

    private val TAG = "YVD_REPO"

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
                    val bestAudioContentLength = if (stream.isVideoOnly) {
                        extractor.audioStreams
                            .filter { it.format?.suffix == "m4a" }
                            .maxByOrNull { it.averageBitrate }
                            ?.let { audio ->
                                audio.itagItem?.contentLength?.takeIf { it > 0L }
                                    ?: ((audio.averageBitrate.toLong() * 1000L * durationSeconds) / 8L)
                            } ?: 0L
                    } else 0L

                    val videoContentLength = stream.itagItem?.contentLength ?: -1L

                    val fileSize = if (videoContentLength > 0L) {
                        formatBytes(videoContentLength + bestAudioContentLength)
                    } else {
                        val estimatedVideoBytes = (stream.bitrate.toLong() * 0.70 * durationSeconds / 8).toLong()
                        "~${formatBytes(estimatedVideoBytes + bestAudioContentLength)}"
                    }

                    VideoFormat(
                        formatId = stream.itag.toString(),
                        ext = stream.format?.suffix ?: "mp4",
                        resolution = stream.resolution,
                        fileSize = fileSize,
                        fps = stream.resolution.substringAfter("p", "30")
                            .replace(Regex("\\D+"), "").toIntOrNull() ?: 30
                    )
                }

            val audioFormats = extractor.audioStreams
                .filter { it.format?.suffix == "m4a" }
                .sortedByDescending { it.averageBitrate }
                .distinctBy { it.averageBitrate }
                .map { stream ->
                    AudioFormat(
                        formatId = stream.itag.toString(),
                        ext = stream.format?.suffix ?: "m4a",
                        bitrate = "${stream.averageBitrate}kbps",
                        fileSize = formatFileSize(
                            itagContentLength = stream.itagItem?.contentLength ?: -1L,
                            bitrateBps = stream.averageBitrate.toLong() * 1000L,
                            durationSec = durationSeconds
                        )
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

    override fun getPlaylistMetadata(url: String): Flow<Resource<PlaylistMetadata>> = flow {
        emit(Resource.Loading())
        try {
            val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
            extractor.fetchPage()

            val videos = mutableListOf<PlaylistVideoItem>()
            var page = extractor.initialPage

            while (true) {
                page.items?.forEach { item ->
                    if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
                        val thumb = item.thumbnails.maxByOrNull { it.width }?.url ?: ""
                        val dur = item.duration.let { d ->
                            if (d <= 0L) "--:--"
                            else "%d:%02d".format(d / 60, d % 60)
                        }
                        videos.add(
                            PlaylistVideoItem(
                                url = item.url,
                                title = item.name,
                                thumbnailUrl = thumb,
                                duration = dur
                            )
                        )
                    }
                }
                if (!page.hasNextPage()) break
                page = extractor.getPage(page.nextPage!!)
            }

            emit(
                Resource.Success(
                    PlaylistMetadata(
                        title = extractor.name,
                        videoCount = videos.size,
                        videos = videos
                    )
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Playlist fetch failed: ${e.message}", e)
            emit(Resource.Error("Could not load playlist: ${e.localizedMessage}"))
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

            val extractor = ServiceList.YouTube.getStreamExtractor(url) as YoutubeStreamExtractor
            extractor.fetchPage()

            val appDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "YVDownloader"
            ).also { if (!it.exists()) it.mkdirs() }

            // ── FIX 4: Disambiguate titles that share the same 50-char prefix ──────────
            // Two playlist items with nearly identical titles (e.g. "Episode 1 — Season 3 …"
            // vs "Episode 2 — Season 3 …" when both truncate to the same first 50 chars) would
            // otherwise produce the same output filename and corrupt each other's download.
            val sanitised = title
                .replace(Regex("[^\\w.\\- ]"), "_")
                .trim()
                .replace(Regex("\\s+"), "_")

            val cleanTitle = if (sanitised.length > 50) {
                // Keep first 44 chars + 6-char hex hash of the FULL sanitised title.
                // This guarantees uniqueness without making filenames unreadably long.
                val hash = sanitised.hashCode().toLong().and(0xFFFFFFL).toString(16).padStart(6, '0')
                "${sanitised.take(44)}_$hash"
            } else {
                sanitised
            }

            val (resolvedFormatId, resolvedIsAudio) = resolveStreamForVideo(extractor, formatId, isAudio)
            if (resolvedIsAudio) {
                handleAudioDownload(extractor, resolvedFormatId, cleanTitle, appDir)
            } else {
                handleVideoDownload(extractor, resolvedFormatId, cleanTitle, appDir)
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

        if (isFileComplete(finalFile)) {
            Log.d(TAG, "Audio already complete, skipping: ${finalFile.name}")
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

        if (!stream.isVideoOnly) {
            if (isFileComplete(finalFile)) {
                Log.d(TAG, "Video already complete, skipping: ${finalFile.name}")
                trySend(DownloadStatus.Progress(100f, "Already downloaded"))
                trySend(DownloadStatus.Success(finalFile))
                return
            }
            downloadStreamParallel(stream.content, finalFile, this, "Downloading video…")
            trySend(DownloadStatus.Success(finalFile))

        } else {
            if (isFileComplete(finalFile)) {
                Log.d(TAG, "Muxed video already complete, skipping: ${finalFile.name}")
                trySend(DownloadStatus.Progress(100f, "Already downloaded"))
                trySend(DownloadStatus.Success(finalFile))
                return
            }

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
                videoTemp.delete()
                audioTemp.delete()
                resumeStateStore.clearState(cacheDir, videoTemp.absolutePath)
                resumeStateStore.clearState(cacheDir, audioTemp.absolutePath)
            }
        }
    }

    // ─── Parallel Download Engine ─────────────────────────────────────────────

    private suspend fun downloadStreamParallel(
        streamUrl: String,
        file: File,
        flow: ProducerScope<DownloadStatus>,
        statusPrefix: String
    ) = withContext(Dispatchers.IO) {

        val downloadId = file.absolutePath
        var totalLength = -1L

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
            // ── FIX 1 (partial) + FIX 3 ──────────────────────────────────────────────
            // downloadSingleThread deletes the partial file on failure (Fix 1).
            // We clear any stale ResumeState that a previous parallel session may have
            // left, so isFileComplete() won't return false for the newly completed file (Fix 3).
            downloadSingleThread(streamUrl, file, totalLength, flow, statusPrefix)
            resumeStateStore.clearState(context.cacheDir, downloadId)
            return@withContext
        }

        val savedState = resumeStateStore.loadState(context.cacheDir, downloadId)
        val chunks: List<ChunkState>

        if (savedState != null && savedState.totalLength == totalLength) {
            val doneCount = savedState.chunks.count { it.completed }
            Log.d(TAG, "Resuming: $doneCount/${savedState.chunks.size} chunks already done")
            chunks = savedState.chunks
            if (!file.exists() || file.length() != totalLength) {
                RandomAccessFile(file, "rw").use { it.setLength(totalLength) }
            }
        } else {
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

        val downloadedBytes = AtomicLong(
            chunks.filter { it.completed }.sumOf { it.end - it.start + 1 }
        )

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

            Log.d(TAG, "Parallel download complete: ${file.name} (${file.length()}B)")
            resumeStateStore.clearState(context.cacheDir, downloadId)

        } catch (e: Exception) {
            Log.e(TAG, "Parallel download interrupted — resume state preserved: ${e.message}")
            throw e
        }
    }

    // ─── Chunk Downloader ─────────────────────────────────────────────────────

    /**
     * Downloads one byte-range chunk and writes it to the correct offset in [file].
     *
     * ## Fix 2 — byte-count validation
     * After writing, the actual number of bytes written is compared against the expected chunk
     * size (`chunk.end - chunk.start + 1`).  A mismatch means the server returned a short body
     * (error payload, premature connection close) without throwing — an IOException is raised
     * so `retryWithBackoff` can retry the chunk.  The chunk is only marked complete when the
     * full expected byte count is confirmed.
     *
     * Without this check, a short body would leave the pre-allocated tail of the chunk as zeros
     * inside the M4A container, corrupting the sample table and producing 0:00 duration.
     *
     * ## Range strategy
     * - YouTube CDN (`googlevideo.com`): append `&range=start-end` as a URL query parameter.
     * - All other servers: standard `Range: bytes=start-end` HTTP header.
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
        val isYouTubeCdn = url.contains("googlevideo.com")
        val expectedBytes = chunk.end - chunk.start + 1

        val requestBuilder = Request.Builder()

        if (isYouTubeCdn) {
            requestBuilder.url(appendYouTubeRangeParam(url, chunk.start, chunk.end))
        } else {
            requestBuilder
                .url(url)
                .header("Range", "bytes=${chunk.start}-${chunk.end}")
        }

        val request = requestBuilder.build()
        val raf = RandomAccessFile(file, "rw")
        raf.seek(chunk.start)

        try {
            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("Chunk $chunkIndex failed with HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty body for chunk $chunkIndex")
            val buffer = ByteArray(IO_BUFFER_SIZE)
            var bytesRead: Int
            // ── FIX 2: Count every byte written for this chunk ──────────────────────────
            var bytesWritten = 0L

            body.byteStream().use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    raf.write(buffer, 0, bytesRead)
                    bytesWritten += bytesRead
                    val total = atomicProgress.addAndGet(bytesRead.toLong())
                    if (totalFileLength > 0 && total % PROGRESS_REPORT_EVERY_BYTES < bytesRead) {
                        val pct = (total.toFloat() / totalFileLength * 100f).coerceIn(0f, 100f)
                        flow.trySend(DownloadStatus.Progress(pct, "$statusPrefix ${pct.toInt()}%"))
                    }
                }
            }

            // ── FIX 2: Validate the server returned the full expected byte range ─────────
            // If the body was shorter than expected (error payload, premature close, CDN
            // returning fewer bytes than requested), throw so retryWithBackoff can retry.
            // Do NOT mark this chunk complete — the pre-allocated zeros would corrupt the file.
            if (bytesWritten != expectedBytes) {
                // Undo the progress counter so the retry doesn't double-count.
                atomicProgress.addAndGet(-bytesWritten)
                // Seek back and zero-fill this chunk so stale data from a previous attempt
                // does not persist in the pre-allocated region.
                raf.seek(chunk.start)
                val zeroBuf = ByteArray(IO_BUFFER_SIZE)
                var remaining = expectedBytes
                while (remaining > 0) {
                    val toWrite = minOf(remaining, zeroBuf.size.toLong()).toInt()
                    raf.write(zeroBuf, 0, toWrite)
                    remaining -= toWrite
                }
                throw IOException(
                    "Chunk $chunkIndex size mismatch: expected $expectedBytes bytes, " +
                            "received $bytesWritten — server likely returned a short/error body"
                )
            }

            resumeStateStore.markChunkComplete(context.cacheDir, downloadId, chunkIndex)
            Log.d(TAG, "Chunk $chunkIndex done [${chunk.start}–${chunk.end}] ($bytesWritten B)")

        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    // ─── Single-Thread Fallback ───────────────────────────────────────────────

    /**
     * Downloads [url] to [file] on a single thread.
     *
     * Used when Content-Length is unavailable or the file is below [SMALL_FILE_THRESHOLD].
     *
     * ## Fix 1 — delete partial file on failure
     * If an exception occurs at any point (network drop, server error, byte-count mismatch),
     * the partial file is **deleted before re-throwing**.  This ensures `isFileComplete()`
     * always returns false for an incomplete single-thread download, preventing the partial
     * file from being served as "already downloaded" on the next Retry — which was the root
     * cause of the 0:00-duration bug for tracks that hit this code path.
     *
     * If the total Content-Length is known and fewer bytes were received than expected, an
     * IOException is raised (and the partial file is deleted) even if the stream ended cleanly
     * via EOF (-1) without throwing — this catches servers that silently close early.
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

        try {
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

            // ── FIX 1: Validate byte count when Content-Length is known ──────────────────
            // Some servers close the connection cleanly after a partial body (EOF via -1
            // rather than by throwing).  The read loop above exits normally, but `copied`
            // is less than `totalLength`.  Treat this as a failure so the partial file is
            // cleaned up and the download can be retried correctly.
            if (totalLength > 0 && copied < totalLength) {
                throw IOException(
                    "Incomplete single-thread download: received $copied of $totalLength bytes"
                )
            }

            Log.d(TAG, "Single-thread download complete: ${file.name} ($copied B)")

        } catch (e: Exception) {
            // ── FIX 1: Delete partial file so isFileComplete() returns false on retry ────
            // Without this, a partial M4A file that is > 1 KB and has no ResumeState is
            // incorrectly reported as "already downloaded", returning a file with 0:00 duration.
            val deleted = file.delete()
            Log.w(TAG, "Single-thread download failed — partial file deleted=$deleted: ${e.message}")
            throw e
        }
    }

    // ─── Retry Helper ─────────────────────────────────────────────────────────

    private suspend fun retryWithBackoff(times: Int, block: () -> Unit) {
        repeat(times - 1) { attempt ->
            try {
                block(); return
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1}/$times failed: ${e.message}. Retrying…")
                delay(1_000L * (attempt + 1))
            }
        }
        block()
    }

    // ─── Muxing ───────────────────────────────────────────────────────────────

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
            File(outPath).delete()
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
     * Returns `true` only when the output file can be considered fully downloaded:
     * it exists, is larger than 1 KB, AND has no pending ResumeState.
     *
     * The ResumeState check is mandatory because parallel downloads pre-allocate the full
     * output file before writing any bytes.  A saved state is proof the download was
     * interrupted regardless of the file's on-disk size.
     *
     * Note: single-thread downloads no longer produce false positives here because Fix 1
     * ensures the partial file is deleted on failure — so `file.exists()` returns false.
     */
    private fun isFileComplete(file: File): Boolean {
        if (!file.exists() || file.length() <= 1_024L) return false
        val hasPendingResumeState =
            resumeStateStore.loadState(context.cacheDir, file.absolutePath) != null
        return !hasPendingResumeState
    }

    private fun formatFileSize(
        itagContentLength: Long,
        bitrateBps: Long,
        durationSec: Long
    ): String {
        if (itagContentLength > 0L) {
            return formatBytes(itagContentLength)
        }
        return estimateFileSize(bitrateBps, durationSec)
    }

    private fun estimateFileSize(bitrateBps: Long, durationSec: Long): String {
        if (bitrateBps <= 0 || durationSec <= 0) return "Unknown"
        val bytes = (bitrateBps * durationSec) / 8
        return "~${formatBytes(bytes)}"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L         -> "%.1f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
            bytes >= 1024L                 -> "%.1f KB".format(bytes.toDouble() / 1024.0)
            else                           -> "$bytes B"
        }
    }

    private fun appendYouTubeRangeParam(url: String, start: Long, end: Long): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "${url}${separator}range=${start}-${end}"
    }

    private fun resolveStreamForVideo(
        extractor: YoutubeStreamExtractor,
        formatId: String,
        isAudio: Boolean
    ): Pair<String, Boolean> {
        if (!formatId.startsWith("QUALITY_")) return Pair(formatId, isAudio)

        val allVideo = extractor.videoStreams + extractor.videoOnlyStreams
        val allAudio = extractor.audioStreams

        fun resolutionInt(s: org.schabi.newpipe.extractor.stream.VideoStream) =
            s.resolution.replace(Regex("p.*"), "").toIntOrNull() ?: 0

        return when (formatId) {
            QUALITY_AUDIO -> {
                val s = allAudio.filter { it.format?.suffix == "m4a" }
                    .maxByOrNull { it.averageBitrate }
                    ?: throw Exception("No M4A audio stream found")
                Pair(s.itag.toString(), true)
            }
            QUALITY_BEST -> {
                val s = allVideo.filter { it.format?.suffix == "mp4" }
                    .maxByOrNull { resolutionInt(it) }
                    ?: throw Exception("No MP4 video stream found")
                Pair(s.itag.toString(), false)
            }
            QUALITY_720P -> {
                val mp4 = allVideo.filter { it.format?.suffix == "mp4" }
                val s = mp4.sortedByDescending { resolutionInt(it) }
                    .firstOrNull { resolutionInt(it) <= 720 }
                    ?: mp4.minByOrNull { resolutionInt(it) }
                    ?: throw Exception("No MP4 video stream found")
                Pair(s.itag.toString(), false)
            }
            QUALITY_480P -> {
                val mp4 = allVideo.filter { it.format?.suffix == "mp4" }
                val s = mp4.sortedByDescending { resolutionInt(it) }
                    .firstOrNull { resolutionInt(it) <= 480 }
                    ?: mp4.minByOrNull { resolutionInt(it) }
                    ?: throw Exception("No MP4 video stream found")
                Pair(s.itag.toString(), false)
            }
            else -> throw Exception("Unknown quality descriptor: $formatId")
        }
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val THREAD_COUNT = 4
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val MUX_BUFFER_SIZE = 2 * 1024 * 1024
        private const val SMALL_FILE_THRESHOLD = 512 * 1024L
        private const val PROGRESS_REPORT_EVERY_BYTES = 256 * 1024L
        private const val RETRY_COUNT = 3

        const val QUALITY_BEST  = "QUALITY_BEST"
        const val QUALITY_720P  = "QUALITY_720P"
        const val QUALITY_480P  = "QUALITY_480P"
        const val QUALITY_AUDIO = "QUALITY_AUDIO"
    }
}