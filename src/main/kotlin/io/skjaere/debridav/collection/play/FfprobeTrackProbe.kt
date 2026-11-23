package io.skjaere.debridav.collection.play

import io.skjaere.nzbstreamer.play.Hdr
import io.skjaere.nzbstreamer.play.PlaybackTarget
import io.skjaere.nzbstreamer.play.ProbeResult
import io.skjaere.nzbstreamer.play.ProbedTrack
import io.skjaere.nzbstreamer.play.ProbedTracks
import io.skjaere.nzbstreamer.play.ProbedVideo
import io.skjaere.nzbstreamer.play.TrackProbe
import io.skjaere.nzbstreamer.play.UnreadableFile
import io.skjaere.nzbstreamer.play.UsenetError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The play loop's "Checking audio and video" step for a browser: ffprobe reads the opened file's tracks (through the
 * session's stream, so only the header and index are fetched), and the release plays when [capabilities] decode its
 * video and at least one audio track as they are. The ffprobe process is the session's: closing the session kills it.
 */
class FfprobeTrackProbe(
    private val ffprobe: String,
    private val capabilities: BrowserCapabilities,
    private val session: PlaySession,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : TrackProbe {
    private val log = LoggerFactory.getLogger(FfprobeTrackProbe::class.java)

    override suspend fun probe(target: PlaybackTarget): ProbeResult {
        // Kept, it is the session's release, which its stream serves to ffprobe.
        target.keep()
        return when (val run = run(target.uri)) {
            is Run.Output -> decide(JSON.decodeFromString<FfprobeOutput>(run.json))
            is Run.Unreadable -> ProbeResult.Failed(UsenetError.Corrupt(run.detail), UnreadableFile.UnknownFormat)
            Run.TimedOut ->
                ProbeResult.Failed(UsenetError.Timeout("ffprobe found no tracks within ${timeout.seconds} s"))
        }
    }

    private fun decide(probed: FfprobeOutput): ProbeResult {
        val videos = probed.streams.filter { it.codecType == "video" && it.disposition["attached_pic"] != 1 }
        val audio = probed.streams.filter { it.codecType == "audio" }
        val tracks = ProbedTracks(
            video = videos.map(::videoTrack),
            audio = audio.map(::audioTrack),
            text = probed.streams.filter { it.codecType == "subtitle" }.map(::textTrack),
        )
        val main = videos.firstOrNull { it.disposition["default"] == 1 } ?: videos.firstOrNull()
        val video = main?.let {
            ProbedVideo(
                fps = fps(it.avgFrameRate),
                durationMs = probed.format?.durationMs(),
                dolbyVision = it.doviProfile() != null,
            )
        }
        return when {
            main == null || !capabilities.decodesVideo(main.codecName, main.tenBit) ->
                ProbeResult.NoPlayableVideo(listOfNotNull(main?.let(::videoLabel)), tracks, video)
            audio.isNotEmpty() && audio.none { capabilities.decodesAudio(it.codecName) } ->
                ProbeResult.NoPlayableAudio(audio.map(::audioLabel).distinct(), tracks, video)
            else -> ProbeResult.Playable(video, tracks)
        }
    }

    private sealed interface Run {
        class Output(val json: String) : Run
        class Unreadable(val detail: String) : Run
        data object TimedOut : Run
    }

    /** ffprobe's JSON for [uri]; its process is killed when the session closes, or when this is cancelled. */
    private suspend fun run(uri: String): Run = withContext(Dispatchers.IO) {
        val errors: File = session.workDir.resolve("ffprobe.log").toFile()
        val process = ProcessBuilder(
            ffprobe, "-v", "error", "-print_format", "json", "-show_streams", "-show_format", uri,
        ).redirectError(ProcessBuilder.Redirect.appendTo(errors)).start()
        val kill = session.own(AutoCloseable { process.destroyForcibly() })
        try {
            val output = async { process.inputStream.readBytes() }
            val finished = runInterruptible { process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) }
            if (!finished) {
                process.destroyForcibly()
                Run.TimedOut
            } else {
                val json = output.await().decodeToString()
                if (process.exitValue() == 0) {
                    Run.Output(json)
                } else {
                    val detail = errors.takeIf { it.exists() }?.readLines()?.lastOrNull { it.isNotBlank() }
                        ?: "ffprobe failed"
                    log.info("play session {}: ffprobe couldn't read {}: {}", session.id, uri, detail)
                    Run.Unreadable(detail)
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            session.disown(kill)
        }
    }

    private fun videoTrack(stream: FfprobeStream) = ProbedTrack(
        mime = MIMES[stream.codecName],
        label = videoLabel(stream),
        language = stream.tags["language"],
        default = stream.disposition["default"] == 1,
        width = stream.width,
        height = stream.height,
        bitDepth = if (stream.tenBit) TEN_BIT else EIGHT_BIT,
        hdr = when {
            stream.doviProfile() != null -> Hdr.DolbyVision
            stream.colorTransfer == "smpte2084" -> Hdr.Hdr10
            stream.colorTransfer == "arib-std-b67" -> Hdr.Hlg
            else -> null
        },
        dolbyVisionProfile = stream.doviProfile(),
    )

    private fun audioTrack(stream: FfprobeStream) = ProbedTrack(
        mime = MIMES[stream.codecName],
        label = audioLabel(stream),
        language = stream.tags["language"],
        default = stream.disposition["default"] == 1,
        forced = stream.disposition["forced"] == 1,
        channels = stream.channels,
    )

    private fun textTrack(stream: FfprobeStream) = ProbedTrack(
        mime = null,
        label = stream.tags["title"] ?: stream.codecName.orEmpty(),
        language = stream.tags["language"],
        default = stream.disposition["default"] == 1,
        forced = stream.disposition["forced"] == 1,
    )

    private fun videoLabel(stream: FfprobeStream): String =
        (VIDEO_NAMES[stream.codecName] ?: stream.codecName.orEmpty().uppercase()) + if (stream.tenBit) " 10-bit" else ""

    private fun audioLabel(stream: FfprobeStream): String {
        val name = AUDIO_NAMES[stream.codecName] ?: stream.profile ?: stream.codecName.orEmpty().uppercase()
        return listOfNotNull(name, stream.channels?.let { CHANNELS[it] ?: "$it ch" }).joinToString(" ")
    }

    private fun fps(rate: String?): Double? {
        val (num, den) = rate?.split('/')?.mapNotNull(String::toDoubleOrNull)?.takeIf { it.size == 2 } ?: return null
        return (num / den).takeIf { den > 0 && it > 0 }
    }

    @Serializable
    private data class FfprobeOutput(val streams: List<FfprobeStream> = emptyList(), val format: FfprobeFormat? = null)

    @Serializable
    private data class FfprobeFormat(val duration: String? = null) {
        fun durationMs(): Long? = duration?.toDoubleOrNull()?.let { (it * MILLIS).toLong() }
    }

    @Serializable
    private data class FfprobeStream(
        @SerialName("codec_name") val codecName: String? = null,
        @SerialName("codec_type") val codecType: String? = null,
        val profile: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        @SerialName("pix_fmt") val pixFmt: String? = null,
        @SerialName("bits_per_raw_sample") val bitsPerRawSample: String? = null,
        val channels: Int? = null,
        @SerialName("avg_frame_rate") val avgFrameRate: String? = null,
        @SerialName("color_transfer") val colorTransfer: String? = null,
        val disposition: Map<String, Int> = emptyMap(),
        val tags: Map<String, String> = emptyMap(),
        @SerialName("side_data_list") val sideData: List<JsonObject> = emptyList(),
    ) {
        val tenBit: Boolean get() = pixFmt?.contains("p10") == true || bitsPerRawSample == "10"

        fun doviProfile(): Int? = sideData.firstOrNull { it.containsKey("dv_profile") }
            ?.get("dv_profile")?.jsonPrimitive?.intOrNull
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(60)
        const val MILLIS = 1000.0
        const val EIGHT_BIT = 8
        const val TEN_BIT = 10

        val JSON = Json { ignoreUnknownKeys = true }

        val VIDEO_NAMES = mapOf(
            "h264" to "H.264", "hevc" to "HEVC", "av1" to "AV1", "vp9" to "VP9",
            "mpeg2video" to "MPEG-2", "vc1" to "VC-1", "mpeg4" to "MPEG-4",
        )
        val AUDIO_NAMES = mapOf(
            "aac" to "AAC", "ac3" to "AC-3", "eac3" to "E-AC-3", "truehd" to "TrueHD", "flac" to "FLAC",
            "opus" to "Opus", "mp3" to "MP3", "vorbis" to "Vorbis", "dts" to "DTS",
        )
        val CHANNELS = mapOf(1 to "mono", 2 to "stereo", 6 to "5.1", 8 to "7.1")
        val MIMES = mapOf(
            "h264" to "video/avc", "hevc" to "video/hevc", "av1" to "video/av01", "vp9" to "video/x-vnd.on2.vp9",
            "aac" to "audio/mp4a-latm", "ac3" to "audio/ac3", "eac3" to "audio/eac3", "opus" to "audio/opus",
            "flac" to "audio/flac", "mp3" to "audio/mpeg",
        )
    }
}
