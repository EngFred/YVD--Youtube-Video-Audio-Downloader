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
        Regex("^(https?://)?(www\\.|m\\.)?youtube\\.com/watch\\?.*v=[a-zA-Z0-9_-]{11}.*$"),
        Regex("^(https?://)?youtu\\.be/[a-zA-Z0-9_-]{11}.*$"),
        Regex("^(https?://)?(www\\.|m\\.)?youtube\\.com/shorts/[a-zA-Z0-9_-]{11}.*$"),
        Regex("^(https?://)?(www\\.|m\\.)?youtube\\.com/live/[a-zA-Z0-9_-]{11}.*$"),
    )
    private val PLAYLIST_PATTERN =
        Regex("^(https?://)?(www\\.|m\\.)?youtube\\.com/playlist\\?.*list=[\\w\\-]+.*$")

    fun isValidYouTubeUrl(url: String): Boolean {
        val t = url.trim()
        return t.isNotBlank() && (YOUTUBE_PATTERNS.any { it.matches(t) } || PLAYLIST_PATTERN.matches(t))
    }

    /** Returns true ONLY for pure playlist URLs (no video watch URLs). */
    fun isPlaylistUrl(url: String): Boolean {
        val t = url.trim()
        return PLAYLIST_PATTERN.matches(t) && YOUTUBE_PATTERNS.none { it.matches(t) }
    }

    fun sanitize(url: String): String {
        val trimmed = url.trim()
        return if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "https://$trimmed"
        } else trimmed
    }
}