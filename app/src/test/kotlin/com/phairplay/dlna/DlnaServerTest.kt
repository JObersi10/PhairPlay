package com.phairplay.dlna

import android.content.Context
import com.phairplay.airplay.NowPlayingInfo
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the three DLNA control-path bugs that made the renderer look alive and behave dead:
 * metadata that never parsed, a volume control that acknowledged and discarded every request, and
 * SCPD service descriptions that evented the wrong variables.
 *
 * These drive the SOAP handlers directly rather than over a socket — the HTTP layer is a thin
 * dispatcher, and binding port 8200 in a unit test would be flaky for no added coverage.
 */
class DlnaServerTest {

    private var lastNowPlaying: NowPlayingInfo? = null

    private fun server() = DlnaServer(
        context = mockk<Context>(relaxed = true),
        onStateChanged = {},
        onNowPlayingChanged = { lastNowPlaying = it }
    )

    /**
     * The real shape of a SetAVTransportURI body: the DIDL-Lite document is entity-escaped inside
     * CurrentURIMetaData, which is why extracting `dc:title` from the raw body found nothing and
     * every track logged `title=null artist=null album=null`.
     */
    private fun setUriBody(
        url: String,
        title: String = "Bluewave",
        artist: String = "Tinlicker",
        album: String = "Cold Enough For Snow"
    ) = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>$url</CurrentURI>
<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;$title&lt;/dc:title&gt;&lt;upnp:artist&gt;$artist&lt;/upnp:artist&gt;&lt;upnp:album&gt;$album&lt;/upnp:album&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
</u:SetAVTransportURI></s:Body></s:Envelope>"""

    @Test
    fun `escaped DIDL metadata is parsed into now-playing`() {
        val s = server()
        s.handleAvt("SetAVTransportURI", setUriBody("http://192.168.0.10:5001/x.m4a"))

        val info = requireNotNull(lastNowPlaying) { "SetAVTransportURI published no now-playing info" }
        assertEquals("Bluewave", info.title)
        assertEquals("Tinlicker", info.artist)
        assertEquals("Cold Enough For Snow", info.album)
    }

    @Test
    fun `an escaped ampersand in a title survives unescaping`() {
        val s = server()
        // "&amp;amp;" is a literal ampersand once, not twice: unescaping "&amp;" before "&lt;"
        // would corrupt titles that legitimately contain markup-looking text.
        s.handleAvt("SetAVTransportURI", setUriBody("http://h/x.m4a", title = "Simon &amp;amp; Garfunkel"))
        assertEquals("Simon &amp; Garfunkel", lastNowPlaying?.title)
    }

    @Test
    fun `a track with no metadata still gets a title from its file name`() {
        val s = server()
        val body = """<s:Envelope><s:Body><u:SetAVTransportURI>
            <CurrentURI>http://192.168.0.10:5001/ums/media/Waiting-All-Night.m4a</CurrentURI>
            <CurrentURIMetaData></CurrentURIMetaData>
            </u:SetAVTransportURI></s:Body></s:Envelope>"""
        s.handleAvt("SetAVTransportURI", body)

        assertEquals("Waiting All Night", lastNowPlaying?.title)
        assertNull(lastNowPlaying?.artist)
    }

    @Test
    fun `SetVolume is applied to the player and read back by GetVolume`() {
        val s = server()
        s.handleRc("SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>37</DesiredVolume>")

        assertEquals(37, s.mediaPlayer.volumePercent)
        assertTrue(
            "GetVolume must report the level actually set, not a hardcoded 100",
            s.handleRc("GetVolume", "").contains("<CurrentVolume>37</CurrentVolume>")
        )
    }

    @Test
    fun `SetMute accepts both the numeric and the boolean spelling`() {
        val s = server()
        s.handleRc("SetMute", "<DesiredMute>1</DesiredMute>")
        assertTrue(s.mediaPlayer.muted)
        assertTrue(s.handleRc("GetMute", "").contains("<CurrentMute>1</CurrentMute>"))

        s.handleRc("SetMute", "<DesiredMute>false</DesiredMute>")
        assertTrue("\"false\" must unmute, not read as a non-empty truthy string", !s.mediaPlayer.muted)
    }

    @Test
    fun `transport state follows Play, Pause and Stop`() {
        val s = server()
        s.handleAvt("SetAVTransportURI", setUriBody("http://h/x.m4a"))

        s.handleAvt("Play", "")
        assertTrue(s.handleAvt("GetTransportInfo", "").contains("<CurrentTransportState>PLAYING</"))

        s.handleAvt("Pause", "")
        assertTrue(s.handleAvt("GetTransportInfo", "").contains("<CurrentTransportState>PAUSED_PLAYBACK</"))

        s.handleAvt("Stop", "")
        assertTrue(s.handleAvt("GetTransportInfo", "").contains("<CurrentTransportState>STOPPED</"))
        assertNull("Stop must clear the now-playing card", lastNowPlaying)
    }

    /**
     * UPnP AV services event through LastChange and nothing else. Declaring TransportState or Volume
     * as evented while the NOTIFY body carries LastChange makes a strict control point — UMS is
     * built on Cling, which is strict — drop the event for a variable it was never told to expect.
     */
    @Test
    fun `only LastChange is declared as an evented variable`() {
        val s = server()
        for (scpd in listOf(s.avtScpd(), s.rcScpd())) {
            val evented = """<stateVariable sendEvents="yes"><name>([^<]+)</name>"""
                .toRegex().findAll(scpd).map { it.groupValues[1] }.toList()
            assertEquals(listOf("LastChange"), evented)
        }
    }

    @Test
    fun `RenderingControl advertises the mute actions it implements`() {
        val scpd = server().rcScpd()
        // A control point offers only what the SCPD lists, so an implemented-but-undeclared action
        // is an action the user can never reach.
        for (action in listOf("SetVolume", "GetVolume", "SetMute", "GetMute")) {
            assertTrue("$action missing from RenderingControl SCPD", scpd.contains("<name>$action</name>"))
        }
    }

    @Test
    fun `AVTransport declares the actions a control point uses to enable its buttons`() {
        val scpd = server().avtScpd()
        // Implemented in handleAvt but previously absent here, which is why a control point showed
        // only a volume slider: it will not invoke an action the descriptor does not declare.
        assertTrue("GetCurrentTransportActions must be declared",
            scpd.contains("<name>GetCurrentTransportActions</name>"))
        assertTrue("GetMediaInfo must be declared", scpd.contains("<name>GetMediaInfo</name>"))
        assertTrue("CurrentTransportActions state variable must exist",
            scpd.contains("<name>CurrentTransportActions</name>"))
    }

    @Test
    fun `every relatedStateVariable in the AVTransport SCPD is declared`() {
        val scpd = server().avtScpd()
        val declared = Regex("<stateVariable[^>]*><name>([A-Za-z_]+)</name>")
            .findAll(scpd).map { it.groupValues[1] }.toSet()
        val referenced = Regex("<relatedStateVariable>([A-Za-z_]+)</relatedStateVariable>")
            .findAll(scpd).map { it.groupValues[1] }.toSet()
        // A dangling reference makes a strict (Cling-based) control point reject the whole service.
        assertEquals(emptySet<String>(), referenced - declared)
    }
}
