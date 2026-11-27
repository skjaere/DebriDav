package io.skjaere.debridav.test.collection

import io.mockk.coVerify
import io.mockk.mockk
import io.skjaere.debridav.collection.hls.BrowserTrackProbe
import io.skjaere.debridav.collection.play.FfprobeTrackProbe
import io.skjaere.debridav.test.collection.FfprobeTrackProbeTest.FileTarget
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.play.ProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** "Checking audio and video" for a browser: only an MKV can be cut into HLS segments. */
class BrowserTrackProbeTest {

    private val tracks = mockk<FfprobeTrackProbe>()
    private val probe = BrowserTrackProbe(tracks, mockk<NzbStreamer>())

    @Test
    fun `a file that isn't an MKV is refused without ffprobe reading it`() = runBlocking<Unit> {
        // What a release's file holds is the uploader's to choose; FFmpeg reads only what it is told it is.
        val result = probe.probe(FileTarget("/release/Movie.2024.1080p.mp4"))
        assertIs<ProbeResult.NoPlayableVideo>(result)
        assertEquals(listOf("MP4 file"), result.found)
        coVerify(exactly = 0) { tracks.probe(any()) }
    }

    @Test
    fun `a file without an extension is refused as of no known type`() = runBlocking<Unit> {
        val result = probe.probe(FileTarget("/release/Movie"))
        assertIs<ProbeResult.NoPlayableVideo>(result)
        assertEquals(listOf("Unknown file type"), result.found)
        coVerify(exactly = 0) { tracks.probe(any()) }
    }
}
