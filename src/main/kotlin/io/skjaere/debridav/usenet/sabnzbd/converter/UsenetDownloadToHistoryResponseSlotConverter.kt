package io.skjaere.debridav.usenet.sabnzbd.converter

import io.skjaere.debridav.usenet.UsenetDownload
import io.skjaere.debridav.usenet.sabnzbd.model.HistorySlot
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class UsenetDownloadToHistoryResponseSlotConverter : Converter<UsenetDownload, HistorySlot> {
    override fun convert(source: UsenetDownload): HistorySlot {
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
