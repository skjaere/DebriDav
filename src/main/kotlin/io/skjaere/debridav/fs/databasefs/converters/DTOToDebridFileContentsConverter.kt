package io.skjaere.debridav.fs.databasefs.converters

import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridTorrentFileContents
import io.skjaere.debridav.fs.DebridUsenetFileContents
import io.skjaere.debridav.fs.databasefs.DebridFileContentsDTO
import io.skjaere.debridav.fs.databasefs.DebridTorrentContentsDTO
import io.skjaere.debridav.fs.databasefs.DebridUsenetContentsDTO
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class DTOToDebridFileContentsConverter(
    val converter: DTOTODebridLinkConverter
) : Converter<DebridFileContentsDTO, DebridFileContents> {


    override fun convert(source: DebridFileContentsDTO): DebridFileContents {
        return when (source) {
            is DebridTorrentContentsDTO -> {
                DebridTorrentFileContents(
                    id = source.id,
                    originalPath = source.originalPath!!,
                    size = source.size!!,
                    modified = source.modified!!,
                    magnet = source.magnet!!,
                    debridLinks = source.debridLinks!!.map { converter.convert(it) }.toMutableList()
                )
            }

            is DebridUsenetContentsDTO -> {
                DebridUsenetFileContents(
                    id = source.id,
                    originalPath = source.originalPath!!,
                    size = source.size!!,
                    modified = source.modified!!,
                    usenetDownloadId = source.usenetDownloadId!!,
                    nzbFileLocation = source.nzbFileLocation!!,
                    hash = source.hash!!,
                    mimeType = source.mimeType,
                    debridLinks = source.debridLinks!!.map { converter.convert(it) }.toMutableList()
                )
            }

            else -> throw IllegalArgumentException("Unsupported debrid-file-contents: $source")
        }
    }
}
