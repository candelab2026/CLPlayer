package com.lastwave.app.data.playlist

import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistImporterTest {
    private val importer = SpotifyPlaylistImporter(
        okHttpClient = OkHttpClient(),
        innerTube = mockk<InnerTubeMusicApi>(),
        spotifyAuthRepository = mockk<SpotifyAuthRepository>(),
    )

    private fun page(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun parsesTracksPageAndSkipsEpisodesAndRemovedTracks() {
        val rows = mutableListOf<ExternalTrackRow>()
        val added = importer.parseTracksPage(
            page(
                """{"items":[
                  {"track":{"type":"track","name":"Song A","artists":[{"name":"X"},{"name":"Y"}],"album":{"name":"Alb"}}},
                  {"track":null},
                  {"track":{"type":"episode","name":"Podcast","artists":[]}},
                  {"track":{"type":"track","name":"  ","artists":[]}},
                  {"track":{"type":"track","name":"Song B","artists":[{"name":"Z"}]}}
                ]}""",
            ),
            rows,
        )
        assertEquals(2, added)
        assertEquals(
            listOf(
                ExternalTrackRow("Song A", "X, Y", "Alb"),
                ExternalTrackRow("Song B", "Z", null),
            ),
            rows,
        )
    }

    @Test
    fun accumulatesAcrossPages() {
        val rows = mutableListOf<ExternalTrackRow>()
        repeat(3) { p ->
            val items = (0 until 100).joinToString(",") {
                """{"track":{"type":"track","name":"T$p-$it","artists":[{"name":"A"}]}}"""
            }
            importer.parseTracksPage(page("""{"items":[$items]}"""), rows)
        }
        assertEquals(300, rows.size)
    }

    @Test
    fun missingItemsYieldsNothing() {
        val rows = mutableListOf<ExternalTrackRow>()
        assertEquals(0, importer.parseTracksPage(page("{}"), rows))
        assertEquals(0, importer.parseTracksPage(null, rows))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun readsTrackTotalFromDescriptionMeta() {
        fun html(desc: String) = """<head><meta property="og:description" content="$desc"/></head>"""
        assertEquals(250, importer.parseTrackTotal(html("Playlist &#183; Mix &#183; 250 items")))
        assertEquals(1200, importer.parseTrackTotal(html("Playlist · Mix · 1,200 songs")))
        assertNull(importer.parseTrackTotal(html("Playlist · Mix")))
        assertNull(importer.parseTrackTotal("<html></html>"))
    }

    @Test
    fun embedParserStillReadsTracks() {
        val data = """{"props":{"pageProps":{"state":{"data":{"entity":{"uri":"spotify:playlist:abc","title":"My Mix",
            "trackList":[{"uri":"spotify:track:1","title":"One","subtitle":"Art"},
            {"uri":"spotify:track:1","title":"One","subtitle":"Art"},
            {"uri":"spotify:track:2","title":"Two","subtitle":"Art2"}]}}}}}}""".replace("\"", "&quot;")
        val result = importer.parseEmbedPage("""<script id="__NEXT_DATA__" type="application/json">$data</script>""")
        assertEquals("My Mix", result.title)
        assertEquals(listOf("One", "Two"), result.rows.map { it.title })
    }
}
