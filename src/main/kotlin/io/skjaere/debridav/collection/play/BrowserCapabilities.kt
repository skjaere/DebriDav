package io.skjaere.debridav.collection.play

import io.skjaere.releases.AudioCodec
import io.skjaere.releases.DeviceCapabilities
import io.skjaere.releases.Hdr
import io.skjaere.releases.StaticDeviceCapabilities
import io.skjaere.releases.VideoCodec

/**
 * What the browser playing a release can decode from an MP4 (fragmented, as the HLS stream is), as the player page
 * finds out with MediaSource.isTypeSupported. Codecs go by ffprobe's names: video h264, hevc, av1, vp9; audio aac, mp3,
 * opus, flac, ac3, eac3. A release plays only if the browser decodes its video as it is; audio it doesn't decode is
 * converted to AAC, so any plays.
 */
data class BrowserCapabilities(
    /** Height of the screen in pixels, e.g. 1080. */
    val displayHeight: Int = DEFAULT_HEIGHT,
    val video: List<String> = listOf("h264"),
    /** Video codecs it decodes at 10 bits as well. */
    val videoTenBit: List<String> = emptyList(),
    val audio: List<String> = listOf("aac", "mp3"),
    /** HDR the screen shows: hdr10, hdr10plus, hlg, dolby-vision; none for an SDR screen. */
    val hdr: List<String> = emptyList(),
) {
    fun decodesVideo(codec: String?, tenBit: Boolean): Boolean =
        codec != null && codec in video && (!tenBit || codec in videoTenBit)

    fun decodesAudio(codec: String?): Boolean = codec != null && codec in audio

    /** The same as release-finder ranks releases by: any audio, as it is converted when the browser can't decode it. */
    fun toDevice(): DeviceCapabilities = StaticDeviceCapabilities(
        displayHeight = displayHeight,
        video = video.mapNotNull(VIDEO_CODECS::get).toSet(),
        videoTenBit = videoTenBit.mapNotNull(VIDEO_CODECS::get).toSet(),
        audio = AudioCodec.entries.toSet(),
        dolbyVision = "dolby-vision" in hdr,
        hdrTypes = hdr.mapNotNull(HDR_TYPES::get).toSet(),
    )

    private companion object {
        const val DEFAULT_HEIGHT = 1080

        val VIDEO_CODECS = mapOf(
            "h264" to VideoCodec.H264,
            "hevc" to VideoCodec.Hevc,
            "av1" to VideoCodec.Av1,
            "vp9" to VideoCodec.Vp9,
        )

        val HDR_TYPES = mapOf(
            "hdr10" to Hdr.Hdr10,
            "hdr10plus" to Hdr.Hdr10Plus,
            "hlg" to Hdr.Hlg,
            "dolby-vision" to Hdr.DolbyVision,
        )
    }
}
