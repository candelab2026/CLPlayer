package com.lastwave.app.data.playlist

import android.util.Log
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "SpotifyImport"

/**
 * Reads the track list of a Spotify playlist and matches it to playable YouTube Music tracks.
 *
 * Supports both:
 * 1. Direct Web API query when authenticated with Spotify account credentials (unlimited track count, private/account playlists).
 * 2. Public embed page scraping fallback: `https://open.spotify.com/embed/playlist/<id>` reading `__NEXT_DATA__`.
 */
@Singleton
class SpotifyPlaylistImporter @Inject constructor(
    okHttpClient: OkHttpClient,
    private val innerTube: InnerTubeMusicApi,
    private val spotifyAuthRepository: SpotifyAuthRepository,
) {
    private val client = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pure parser: Spotify embed HTML in, parsed playlist out. Performs no
     * network and never throws — unreadable input yields an empty [rows] list so
     * the caller can show a friendly "could not read that playlist" message.
     */
    internal fun parseEmbedPage(html: String, fallbackTitle: String = "Spotify Playlist"): ExternalPlaylistResult {
        val payload = NEXT_DATA.find(html)?.groupValues?.getOrNull(1)?.let(::unescapeHtml)
        if (payload.isNullOrBlank()) {
            Log.w(TAG, "No __NEXT_DATA__ payload in Spotify embed page")
            return emptyResult(fallbackTitle)
        }
        val root = try {
            json.parseToJsonElement(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable __NEXT_DATA__ JSON", e)
            return emptyResult(fallbackTitle)
        }
        var title: String? = null
        val rows = mutableListOf<ExternalTrackRow>()
        val seenUris = mutableSetOf<String>()
        walk(root) parseRow@{ element ->
            val uri = element.text("uri") ?: return@parseRow
            val name = element.text("title") ?: return@parseRow
            when {
                uri.startsWith(SPOTIFY_PLAYLIST_URI) -> {
                    if (title == null) title = name.clean()
                }
                uri.startsWith(SPOTIFY_TRACK_URI) -> {
                    val trackTitle = name.clean()
                    if (trackTitle.isBlank() || !seenUris.add(uri)) return@parseRow
                    rows += ExternalTrackRow(
                        title = trackTitle,
                        artist = element.text("subtitle").orEmpty().clean(),
                    )
                }
            }
        }
        return ExternalPlaylistResult(
            source = ExternalPlaylistSource.SPOTIFY,
            title = title?.takeIf(String::isNotBlank) ?: fallbackTitle,
            rows = rows,
        )
    }

    /**
     * Downloads playlist metadata and tracks. Uses authenticated Spotify Web API when available,
     * otherwise falls back to public embed page scraping.
     */
    suspend fun fetchPlaylist(urlOrId: String): ExternalPlaylistResult = withContext(Dispatchers.IO) {
        var input = urlOrId.trim()
        if (input.contains("spotify.link")) {
            val resolveReq = Request.Builder().url(input).head().build()
            runCatching {
                client.newCall(resolveReq).execute().use { resp ->
                    input = resp.request.url.toString()
                }
            }
        }
        val id = ExternalPlaylistLink.extractId(input, ExternalPlaylistSource.SPOTIFY)
            ?: throw IllegalArgumentException("That does not look like a Spotify playlist link.")

        // First attempt via authenticated Spotify API if personal token is available
        val token = spotifyAuthRepository.getPersonalToken()
        if (!token.isNullOrBlank()) {
            val apiResult = runCatching { fetchViaSpotifyApi(id, token) }.getOrNull()
            if (apiResult != null && apiResult.rows.isNotEmpty()) {
                return@withContext apiResult
            }
        }

        // Fallback to public embed page scraping
        val request = Request.Builder()
            .url("$EMBED_BASE$id")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html")
            .get()
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (response.code != 200) {
                throw IOException("Spotify playlist page returned HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
        val parsed = parseEmbedPage(html)
        if (parsed.rows.size < EMBED_TRACK_LIMIT) return@withContext parsed

        // The embed page caps at ~100 tracks; look up the real size so the UI can warn.
        val total = runCatching { fetchPublicTrackTotal(id) }.getOrNull()
        parsed.copy(
            totalTracks = total,
            truncated = total == null || total > parsed.rows.size,
        )
    }

    private fun fetchPublicTrackTotal(id: String): Int? {
        val request = Request.Builder()
            .url("$PLAYLIST_PAGE_BASE$id")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html")
            .get()
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string().orEmpty()
        }
        return parseTrackTotal(html)
    }

    /** Reads "250 items" / "1,200 songs" style counts from the page's description meta tag. */
    internal fun parseTrackTotal(html: String): Int? {
        val description = OG_DESCRIPTION.find(html)?.groupValues?.getOrNull(1)?.let(::unescapeHtml) ?: return null
        val digits = TRACK_COUNT.find(description)?.groupValues?.getOrNull(1)
            ?.filter(Char::isDigit)?.takeIf(String::isNotEmpty) ?: return null
        return digits.toIntOrNull()
    }

    private fun fetchViaSpotifyApi(id: String, token: String): ExternalPlaylistResult? {
        val meta = getJson(
            "https://api.spotify.com/v1/playlists/$id?fields=name,owner(display_name),images,tracks(total)",
            token,
        ) ?: return null
        val title = meta["name"]?.jsonPrimitive?.contentOrNull ?: "Spotify Playlist"
        val author = meta["owner"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull
        val artwork = meta["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val total = meta["tracks"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull

        val rows = mutableListOf<ExternalTrackRow>()
        var nextUrl: String? = "https://api.spotify.com/v1/playlists/$id/tracks?limit=$API_PAGE_SIZE&offset=0"
        var pages = 0
        while (!nextUrl.isNullOrBlank() && pages < MAX_API_PAGES) {
            pages++
            val page = getJson(nextUrl, token) ?: break
            parseTracksPage(page, rows)
            nextUrl = page["next"]?.jsonPrimitive?.contentOrNull
        }

        return ExternalPlaylistResult(
            source = ExternalPlaylistSource.SPOTIFY,
            title = title,
            author = author,
            artworkUrl = artwork,
            rows = rows,
            totalTracks = total,
            truncated = total != null && total > rows.size,
        )
    }

    /**
     * GET [url] as a JSON object, waiting out Spotify's 429 rate limit (honouring
     * `Retry-After`, capped) a few times. Null on any other failure.
     */
    private fun getJson(url: String, token: String): JsonObject? {
        repeat(RATE_LIMIT_RETRIES + 1) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .build()
            var waitMs = -1L
            val body = client.newCall(request).execute().use { response ->
                when {
                    response.code == 429 -> {
                        val seconds = response.header("Retry-After")?.trim()?.toLongOrNull() ?: 1L
                        waitMs = (seconds * 1000).coerceIn(500L, MAX_RETRY_WAIT_MS)
                        null
                    }
                    !response.isSuccessful -> null
                    else -> response.body?.string()
                }
            }
            if (waitMs < 0) {
                if (body.isNullOrBlank()) return null
                return try {
                    json.parseToJsonElement(body) as? JsonObject
                } catch (e: Exception) {
                    Log.w(TAG, "Unreadable Spotify API response", e)
                    null
                }
            }
            Thread.sleep(waitMs)
        }
        return null
    }

    /**
     * Pure parser for one page of `items` (`{ track: {...} }` entries). Skips removed
     * tracks and podcast episodes; returns how many rows were appended.
     */
    internal fun parseTracksPage(container: JsonObject?, outList: MutableList<ExternalTrackRow>): Int {
        val items = container?.get("items") as? JsonArray ?: return 0
        val before = outList.size
        for (item in items) {
            val trackObj = (item as? JsonObject)?.get("track") as? JsonObject ?: continue
            if (trackObj.text("type") == "episode") continue
            val name = trackObj.text("name")?.clean()?.takeIf(String::isNotBlank) ?: continue
            val artists = (trackObj["artists"] as? JsonArray)?.mapNotNull {
                (it as? JsonObject)?.text("name")?.clean()?.takeIf(String::isNotBlank)
            }?.joinToString(", ").orEmpty()
            val album = (trackObj["album"] as? JsonObject)?.text("name")?.clean()
            outList += ExternalTrackRow(title = name, artist = artists, album = album)
        }
        return outList.size - before
    }

    /**
     * Fetches playlists from the connected Spotify account library.
     */
    suspend fun fetchUserPlaylists(): List<SpotifyUserPlaylist> = withContext(Dispatchers.IO) {
        val token = spotifyAuthRepository.getPersonalToken() ?: return@withContext emptyList()
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=50")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                val items = root["items"]?.jsonArray.orEmpty()
                items.mapNotNull { itemElement ->
                    val obj = itemElement as? JsonObject ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val desc = obj["description"]?.jsonPrimitive?.contentOrNull
                    val img = obj["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    val count = obj["tracks"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull ?: 0
                    SpotifyUserPlaylist(
                        id = id,
                        name = name,
                        description = desc,
                        artworkUrl = img,
                        trackCount = count,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch user Spotify playlists: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches the user's Spotify Liked Songs collection.
     */
    suspend fun fetchLikedSongs(): ExternalPlaylistResult = withContext(Dispatchers.IO) {
        val token = spotifyAuthRepository.getPersonalToken()
            ?: throw IllegalStateException("Spotify account is not connected.")

        val rows = mutableListOf<ExternalTrackRow>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
        var pages = 0
        var total: Int? = null

        while (!nextUrl.isNullOrBlank() && pages < MAX_LIKED_PAGES) {
            pages++
            val page = getJson(nextUrl, token) ?: break
            if (total == null) total = page["total"]?.jsonPrimitive?.intOrNull
            parseTracksPage(page, rows)
            nextUrl = page["next"]?.jsonPrimitive?.contentOrNull
        }

        ExternalPlaylistResult(
            source = ExternalPlaylistSource.SPOTIFY,
            title = "Spotify Liked Songs",
            author = "Spotify",
            rows = rows,
            totalTracks = total,
            truncated = total != null && total > rows.size,
        )
    }

    /**
     * Fetches and matches the user's Spotify Liked Songs to playable YouTube Music tracks.
     */
    suspend fun fetchAndMatchLikedSongs(): ExternalImportResult = withContext(Dispatchers.IO) {
        val playlist = fetchLikedSongs()
        matchRowsToTracks(playlist.rows, playlist.title)
    }

    /**
     * Fetches the playlist and resolves every row to a playable YouTube Music
     * track, mirroring [CsvPlaylistImporter]'s bounded fan-out.
     */
    suspend fun fetchAndMatch(urlOrId: String): ExternalImportResult = withContext(Dispatchers.IO) {
        val playlist = fetchPlaylist(urlOrId)
        matchRowsToTracks(playlist.rows, playlist.title)
    }

    private suspend fun matchRowsToTracks(
        rows: List<ExternalTrackRow>,
        suggestedTitle: String,
    ): ExternalImportResult = coroutineScope {
        val limiter = Semaphore(4)
        val tracks = rows.map { row ->
            async {
                limiter.withPermit {
                    try {
                        innerTube.findBestMatchOrNull(row.title, row.artist, prefetchStreams = false)?.let { match ->
                            GeneratedTrack(
                                name = row.title,
                                artist = row.artist,
                                album = row.album ?: match.album,
                                artworkUrl = match.artworkUrl,
                                url = "https://music.youtube.com/watch?v=${match.videoId}",
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()

        ExternalImportResult(
            source = ExternalPlaylistSource.SPOTIFY,
            suggestedTitle = suggestedTitle,
            totalRows = rows.size,
            matchedCount = tracks.size,
            tracks = tracks,
        )
    }

    /** Depth-first walk over the parsed JSON tree, visiting every object once. */
    private fun walk(root: JsonElement, visit: (JsonObject) -> Unit) {
        val pending = mutableListOf(root)
        while (pending.isNotEmpty()) {
            when (val current = pending.removeAt(pending.size - 1)) {
                is JsonObject -> {
                    visit(current)
                    pending.addAll(current.values)
                }
                is JsonArray -> pending.addAll(current)
                else -> Unit
            }
        }
    }

    /** String value of [key], or null when absent, null or not a primitive. */
    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    /** Collapses non-breaking spaces (Spotify pads artist lists with them) and trims. */
    private fun String.clean(): String = replace('\u00a0', ' ').trim()

    /** The embed JSON is HTML-escaped inside the script tag; undo the common entities. */
    private fun unescapeHtml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun emptyResult(fallbackTitle: String) = ExternalPlaylistResult(
        source = ExternalPlaylistSource.SPOTIFY,
        title = fallbackTitle,
        rows = emptyList(),
    )

    private companion object {
        const val EMBED_BASE = "https://open.spotify.com/embed/playlist/"
        const val PLAYLIST_PAGE_BASE = "https://open.spotify.com/playlist/"
        /** The public embed page lists at most this many tracks. */
        const val EMBED_TRACK_LIMIT = 100
        const val API_PAGE_SIZE = 100
        const val MAX_API_PAGES = 100
        const val MAX_LIKED_PAGES = 400
        const val RATE_LIMIT_RETRIES = 3
        const val MAX_RETRY_WAIT_MS = 10_000L
        const val SPOTIFY_TRACK_URI = "spotify:track:"
        const val SPOTIFY_PLAYLIST_URI = "spotify:playlist:"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val OG_DESCRIPTION = Regex("""<meta[^>]*property=["']og:description["'][^>]*content=["']([^"']*)["']""")
        val TRACK_COUNT = Regex("""(\d[\d.,]*)\s*(?:items?|songs?|tracks?|brani)""", RegexOption.IGNORE_CASE)
        val NEXT_DATA = Regex(
            """<script[^>]*\bid=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
