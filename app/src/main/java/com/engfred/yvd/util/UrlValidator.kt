package com.engfred.yvd.util

/**
 * Validates and sanitizes YouTube URLs before any network calls are made.
 *
 * Catching invalid input here means:
 * - Zero wasted network requests on obviously wrong input.
 * - Instant, clear error messages ("Please paste a valid YouTube link")
 *   instead of a confusing NewPipe exception surfacing to the user.
 *
 * Handles all common YouTube URL formats:
 * - Standard watch:   youtube.com/watch?v=xxxxxxxxxxx
 * - Mobile watch:     m.youtube.com/watch?v=xxxxxxxxxxx
 * - Shortened:        youtu.be/xxxxxxxxxxx
 * - Shorts:           youtube.com/shorts/xxxxxxxxxxx
 */
object UrlValidator {

    private val YOUTUBE_PATTERNS = listOf(
        // Standard / mobile watch URL
        Regex("^(https?://)?(www\\.|m\\.)?youtube\\.com/watch\\?.*v=[a-zA-Z0-9_-]{11}.*$"),
        // Shortened share URL
        Regex("^(https?://)?youtu\\.be/[a-zA-Z0-9_-]{11}.*$"),
        // YouTube Shorts
        Regex("^(https?://)?(www\\.|m\\.)?youtube\\.com/shorts/[a-zA-Z0-9_-]{11}.*$"),
    )

    /**
     * Returns true if [url] matches any known YouTube URL format.
     * Call [sanitize] first to normalize the URL before validating.
     */
    fun isValidYouTubeUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        return YOUTUBE_PATTERNS.any { it.matches(trimmed) }
    }

    /**
     * Normalizes a URL string for use with NewPipe.
     *
     * - Trims whitespace.
     * - Adds `https://` if the user pasted a bare domain (e.g., `youtu.be/xxxx`).
     *
     * This does NOT validate the URL — call [isValidYouTubeUrl] separately.
     */
    fun sanitize(url: String): String {
        val trimmed = url.trim()
        return if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }
}