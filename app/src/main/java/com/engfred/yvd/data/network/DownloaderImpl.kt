package com.engfred.yvd.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custom network bridge for NewPipe Extractor.
 *
 * Adapts NewPipe's internal HTTP calls to our shared [OkHttpClient].
 *
 * **Why @Singleton + @Inject:**
 * The entire app — NewPipe metadata extraction AND the parallel file downloader
 * in [com.engfred.yvd.data.repository.YoutubeRepositoryImpl] — shares this single
 * instance. This guarantees one connection pool and one cookie jar for the lifetime
 * of the app, which is critical for performance and session consistency.
 *
 * [com.engfred.yvd.YVDApplication] calls `NewPipe.init(this)` exactly once.
 * No other class should ever call `NewPipe.init()`.
 *
 * Key optimizations:
 * - **Connection pooling**: reuses TCP connections, reducing TLS handshake latency.
 * - **Cookie persistence**: handles YouTube GDPR / consent redirects.
 * - **Generous timeouts**: resilient on unstable mobile networks.
 */
@Singleton
class DownloaderImpl @Inject constructor() : Downloader() {

    private val cookieStore = HashMap<String, List<Cookie>>()

    private val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(connectionPool)
        .retryOnConnectionFailure(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Exposes the underlying [OkHttpClient] so the repository can share
     * the same connection pool for file downloads.
     */
    fun getOkHttpClient(): OkHttpClient = client

    /**
     * Executes a request initiated by the NewPipe Extractor library.
     * Maps NewPipe's [NewPipeRequest] to OkHttp's [Request] and back.
     */
    override fun execute(request: NewPipeRequest): NewPipeResponse {
        val requestBuilder = Request.Builder().url(request.url())

        // Forward all headers from NewPipe
        request.headers().forEach { (key, values) ->
            values.forEach { value -> requestBuilder.addHeader(key, value) }
        }

        // Inject a desktop User-Agent if missing to prevent bot-detection blocks
        if (request.headers()["User-Agent"].isNullOrEmpty()) {
            requestBuilder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
        }

        val dataToSend = request.dataToSend()
        val httpMethod = request.httpMethod()

        when {
            dataToSend != null ->
                requestBuilder.method(httpMethod, dataToSend.toRequestBody(null, 0, dataToSend.size))
            httpMethod == "POST" || httpMethod == "PUT" ->
                requestBuilder.method(httpMethod, ByteArray(0).toRequestBody(null, 0, 0))
        }

        val response = client.newCall(requestBuilder.build()).execute()

        return NewPipeResponse(
            response.code,
            response.message,
            response.headers.toMultimap(),
            response.body?.string() ?: "",
            response.request.url.toString()
        )
    }
}