package io.skjaere.debridav.sabnzbd.converter

import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.sabnzbd.HistorySlot
import io.skjaere.debridav.sabnzbd.UsenetDownload
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class UsenetDownloadToHistoryResponseSlotConverter(
    private val debridavConfiguration: DebridavConfiguration
) : Converter<UsenetDownload, HistorySlot> {
    override fun convert(source: UsenetDownload): HistorySlot? {
        return HistorySlot(
            status = source.status.toString(),
            nzoId = "${source.id!!}",
            downloadTime = 10,
            name = source.name!!,
            failMessage = "",
            bytes = source.size ?: 0L,
            category = source.category!!.name!!,
            nzbName = "${source.name}.nzb",
            storage = source.storagePath ?: ""
        )
    }
}
