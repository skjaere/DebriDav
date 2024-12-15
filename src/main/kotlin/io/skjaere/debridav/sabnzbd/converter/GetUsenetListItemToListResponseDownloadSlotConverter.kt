package io.skjaere.debridav.sabnzbd.converter

import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetListItem
import io.skjaere.debridav.sabnzbd.ListResponseDownloadSlot
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class GetUsenetListItemToListResponseDownloadSlotConverter : Converter<GetUsenetListItem, ListResponseDownloadSlot> {

    @Suppress("MagicNumber")
    override fun convert(source: GetUsenetListItem): ListResponseDownloadSlot? {
        val sizeInMb = source.size / (1 shl 20)
        val mbLeft = 0
        val eta = Duration.ofSeconds(source.eta)

        return ListResponseDownloadSlot(
            status = source.downloadState,
            size = bytesToHumanReadableSize(source.size),
            mb = "%".format(sizeInMb),
            cat = "defaultCat",
            directUnpack = "10/10",
            mbMissing = "0",
            percentage = source.progress.toString(),
            unpackOpts = "3",
            index = 0,
            nzoId = "nzoId",
            labels = listOf(),
            mbLeft = mbLeft.toString(),
            script = "",
            filename = source.name,
            password = "",
            priority = "0",
            sizeLeft = bytesToHumanReadableSize((mbLeft * 1024 * 1024).toLong()),
            timeLeft = "${eta.toHoursPart()}:${eta.toMinutesPart()}:${eta.toSecondsPart()}",
            avgAge = "1d"
        )
    }

    @Suppress("MagicNumber")
    private fun bytesToHumanReadableSize(bytes: Long) = when {
        bytes >= 1 shl 30 -> "%.1f GB".format(bytes / (1 shl 30))
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1 shl 20))
        bytes >= 1 shl 10 -> "%.0f kB".format(bytes / (1 shl 10))
        else -> "$bytes bytes"
    }
}
