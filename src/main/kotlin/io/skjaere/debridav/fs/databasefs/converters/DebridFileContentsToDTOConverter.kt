package io.skjaere.debridav.fs.databasefs.converters

import io.skjaere.debridav.fs.DebridCachedContentFileContents
import io.skjaere.debridav.fs.DebridCachedUsenetFileContents
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridUsenetFileContents
import io.skjaere.debridav.fs.databasefs.DebridFileContentsDTO
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class DebridFileContentsToDTOConverter(
    private val debridTorrentFileContentsDTOConverter: DebridTorrentFileContentsDTOConverter,
    private val debridUsenetFileContentsDTOConverter: DebridUsenetFileContentsDTOConverter
) : Converter<DebridFileContents, DebridFileContentsDTO> {

    override fun convert(source: DebridFileContents): DebridFileContentsDTO {
        return when (source) {
            is DebridCachedContentFileContents -> debridTorrentFileContentsDTOConverter.convert(source)!!
            is DebridUsenetFileContents -> debridUsenetFileContentsDTOConverter.convert(source)!!
            //is DebridCachedUsenetFileContents -> debridUsenetFileContentsDTOConverter.convert(source)!!
            is DebridCachedUsenetFileContents -> TODO()
        }
    }
}
