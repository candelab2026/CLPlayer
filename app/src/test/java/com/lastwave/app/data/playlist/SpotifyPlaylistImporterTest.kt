package com.lastwave.app.data.playlist

import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyPlaylistImporterTest {
    private val importer = SpotifyPlaylistImporter(
        okHttpClient = OkHttpClient(),
        innerTube = mockk<InnerTubeMusicApi>(),
        spotifyAuthRepository = mockk<SpotifyAuthRepository>(),
    )

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
