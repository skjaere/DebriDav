package io.skjaere.debridav.collection.hls

import io.skjaere.nzbstreamer.play.ProbedTrack

/**
 * The audio track a release plays with: its default one (the first, if none is marked), as it is when the browser
 * decodes it, converted to AAC stereo when it doesn't. Not another track the browser does decode: that is often a
 * commentary, or a language the release doesn't play in by default, and converting costs little.
 */
data class AudioChoice(
    /** Which audio track, as FFmpeg numbers them (0:a:N). */
    val stream: Int,
    val convert: Boolean,
) {
    companion object {
        /** What converted audio is, as the player shows it. */
        const val CONVERTED_TO = "AAC stereo"

        /** The choice among [tracks], in FFmpeg's order; none for a release without audio. */
        fun of(tracks: List<ProbedTrack>, decodes: (ProbedTrack) -> Boolean): AudioChoice? {
            if (tracks.isEmpty()) return null
            val stream = tracks.indexOfFirst { it.default }.takeIf { it >= 0 } ?: 0
            return AudioChoice(stream, convert = !decodes(tracks[stream]))
        }
    }
}
