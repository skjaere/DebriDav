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
 * "Checking audio and video" for a browser: what [tracks] says, and then that the file can be cut into HLS segments,
 * which takes an MKV with Cues ([MatroskaIndex]). A release that can't fails here, so the play goes on to the next.
 * The index of each file that passes is kept for its HLS stream ([indexOf]).
 */
class BrowserTrackProbe(
    private val tracks: FfprobeTrackProbe,
    private val nzbStreamer: NzbStreamer,
) : TrackProbe {
    private val indexes = ConcurrentHashMap<PlaybackTarget, MatroskaIndex>()

    fun indexOf(target: PlaybackTarget): MatroskaIndex? = indexes[target]

    override suspend fun probe(target: PlaybackTarget): ProbeResult {
        val result = tracks.probe(target)
        return if (result is ProbeResult.Playable) indexed(target, result) else result
    }

    /** [result] once [target]'s file is indexed for HLS segments; [ProbeResult.NoPlayableVideo] if it can't be. */
    private suspend fun indexed(target: PlaybackTarget, result: ProbeResult.Playable): ProbeResult {
        val extension = target.file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val matroska = extension in MATROSKA
        val release = (target as? SessionTarget)?.release
        val index = if (matroska && release != null) MatroskaIndex.read(ReleaseBytes(nzbStreamer, release)) else null
        if (index == null) {
            val video = result.tracks?.video?.firstOrNull()?.label ?: "Video"
            val why = if (matroska) {
                "$video in an MKV without cues"
            } else {
                "$video in an ${extension.uppercase(Locale.ROOT)}"
            }
            return ProbeResult.NoPlayableVideo(listOf(why), result.tracks, result.video)
        }
        indexes[target] = index
        return result
    }

    private companion object {
        val MATROSKA = setOf("mkv", "webm")
    }
}
