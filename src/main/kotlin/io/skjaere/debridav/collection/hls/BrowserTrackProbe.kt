package io.skjaere.debridav.collection.hls

import io.skjaere.debridav.collection.play.FfprobeTrackProbe
import io.skjaere.debridav.collection.play.SessionTarget
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.play.PlaybackTarget
import io.skjaere.nzbstreamer.play.ProbeResult
import io.skjaere.nzbstreamer.play.TrackProbe
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * "Checking audio and video" for a browser: only an MKV can be cut into HLS segments, so a file that isn't one fails
 * at once, unread. An MKV then plays when [tracks] says so and it has Cues to cut it at ([MatroskaIndex]). A release
 * that can't fails here, so the play goes on to the next. The index of each file that passes is kept for its HLS
 * stream ([indexOf]).
 */
class BrowserTrackProbe(
    private val tracks: FfprobeTrackProbe,
    private val nzbStreamer: NzbStreamer,
) : TrackProbe {
    private val indexes = ConcurrentHashMap<PlaybackTarget, MatroskaIndex>()

    fun indexOf(target: PlaybackTarget): MatroskaIndex? = indexes[target]

    override suspend fun probe(target: PlaybackTarget): ProbeResult {
        val extension = target.file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // Not handed to ffprobe: what a release's file holds is the uploader's to choose, and only an MKV is read.
        if (extension !in MATROSKA) return ProbeResult.NoPlayableVideo(listOf(kindOf(extension)))
        val result = tracks.probe(target)
        return if (result is ProbeResult.Playable) indexed(target, result) else result
    }

    /** [result] once [target]'s file is indexed for HLS segments; [ProbeResult.NoPlayableVideo] if it can't be. */
    private suspend fun indexed(target: PlaybackTarget, result: ProbeResult.Playable): ProbeResult {
        val release = (target as? SessionTarget)?.release
        val index = release?.let { MatroskaIndex.read(ReleaseBytes(nzbStreamer, it)) }
        if (index == null) {
            val video = result.tracks?.video?.firstOrNull()?.label ?: "Video"
            return ProbeResult.NoPlayableVideo(listOf("$video in an MKV without cues"), result.tracks, result.video)
        }
        indexes[target] = index
        return result
    }

    private companion object {
        val MATROSKA = setOf("mkv", "webm")

        /** A file's type in words, as the play loop words a Cast device's: "MP4 file". */
        fun kindOf(extension: String) =
            if (extension.isEmpty()) "Unknown file type" else "${extension.uppercase(Locale.ROOT)} file"
    }
}
