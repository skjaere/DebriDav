package io.skjaere.debridav.fs.databasefs.converters

import io.skjaere.debridav.fs.DebridUsenetFileContents
import io.skjaere.debridav.fs.databasefs.DebridUsenetContentsDTO
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class DebridUsenetFileContentsDTOConverter(
    private val debridLinkToDTOConverter: DebridLinkToDTOConverter
) : Converter<DebridUsenetFileContents, DebridUsenetContentsDTO> {
    override fun convert(source: DebridUsenetFileContents): DebridUsenetContentsDTO? {
        val debridUsenetFileContents = DebridUsenetContentsDTO()

        debridUsenetFileContents.id = source.id
        debridUsenetFileContents.hash = source.hash
        debridUsenetFileContents.size = source.size
        debridUsenetFileContents.modified = source.modified
        debridUsenetFileContents.usenetDownloadId = source.usenetDownloadId
        debridUsenetFileContents.nzbFileLocation = source.nzbFileLocation
        debridUsenetFileContents.originalPath = source.originalPath
        debridUsenetFileContents.debridLinks = source.debridLinks.map { debridLinkToDTOConverter.convert(it)!! }
        return debridUsenetFileContents
    }
}
