package io.skjaere.debridav.fs.databasefs.converters

import io.skjaere.debridav.fs.DebridCachedContentFileContents
import io.skjaere.debridav.fs.databasefs.DebridCachedTorrentContentDTO
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class DebridTorrentFileContentsDTOConverter(
    private val debridLinkToDTOConverter: DebridLinkToDTOConverter
) : Converter<DebridCachedContentFileContents, DebridCachedTorrentContentDTO> {
    override fun convert(source: DebridCachedContentFileContents): DebridCachedTorrentContentDTO? {
        val debridTorrentFileContents = DebridCachedTorrentContentDTO()

        debridTorrentFileContents.id = source.id
        debridTorrentFileContents.magnet = source.key
        debridTorrentFileContents.size = source.size
        debridTorrentFileContents.modified = source.modified
        debridTorrentFileContents.originalPath = source.originalPath
        debridTorrentFileContents.debridLinks = source.debridLinks.map { debridLinkToDTOConverter.convert(it)!! }
        return debridTorrentFileContents
    }
}
