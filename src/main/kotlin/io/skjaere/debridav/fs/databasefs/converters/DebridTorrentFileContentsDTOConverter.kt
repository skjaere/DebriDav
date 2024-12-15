package io.skjaere.debridav.fs.databasefs.converters

import io.skjaere.debridav.fs.DebridTorrentFileContents
import io.skjaere.debridav.fs.databasefs.DebridTorrentContentsDTO
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class DebridTorrentFileContentsDTOConverter(
    private val debridLinkToDTOConverter: DebridLinkToDTOConverter
) : Converter<DebridTorrentFileContents, DebridTorrentContentsDTO> {
    override fun convert(source: DebridTorrentFileContents): DebridTorrentContentsDTO? {
        val debridTorrentFileContents = DebridTorrentContentsDTO()

        debridTorrentFileContents.id = source.id
        debridTorrentFileContents.magnet = source.magnet
        debridTorrentFileContents.size = source.size
        debridTorrentFileContents.modified = source.modified
        debridTorrentFileContents.originalPath = source.originalPath
        debridTorrentFileContents.debridLinks = source.debridLinks.map { debridLinkToDTOConverter.convert(it)!! }
        return debridTorrentFileContents
    }
}
