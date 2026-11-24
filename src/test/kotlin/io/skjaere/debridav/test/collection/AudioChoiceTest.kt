package io.skjaere.debridav.test.collection

import io.skjaere.debridav.collection.hls.AudioChoice
import io.skjaere.nzbstreamer.play.ProbedTrack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioChoiceTest {

    private fun track(mime: String, default: Boolean = false) = ProbedTrack(mime = mime, label = mime, default = default)

    private val decodesAac: (ProbedTrack) -> Boolean = { it.mime == "audio/mp4a-latm" }

    @Test
    fun `the default track, as it is, when the browser decodes it`() {
        val tracks = listOf(track("audio/eac3"), track("audio/mp4a-latm", default = true))
        assertEquals(AudioChoice(stream = 1, convert = false), AudioChoice.of(tracks, decodesAac))
    }

    @Test
    fun `the default track, converted, when the browser doesn't decode it, though it decodes another`() {
        // The other is often a commentary, or a language the release doesn't play in by default.
        val tracks = listOf(track("audio/eac3", default = true), track("audio/mp4a-latm"))
        assertEquals(AudioChoice(stream = 0, convert = true), AudioChoice.of(tracks, decodesAac))
    }

    @Test
    fun `the first track when none is the default`() {
        val tracks = listOf(track("audio/ac3"), track("audio/mp4a-latm"))
        assertEquals(AudioChoice(stream = 0, convert = true), AudioChoice.of(tracks, decodesAac))
    }

    @Test
    fun `none for a release without audio`() {
        assertNull(AudioChoice.of(emptyList(), decodesAac))
    }
}
