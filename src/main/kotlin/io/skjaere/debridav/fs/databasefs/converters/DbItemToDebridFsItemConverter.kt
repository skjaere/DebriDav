package io.skjaere.debridav.fs.databasefs.converters

import io.skjaere.debridav.fs.DebridFsDirectory
import io.skjaere.debridav.fs.DebridFsFile
import io.skjaere.debridav.fs.DebridFsItem
import io.skjaere.debridav.fs.DebridFsLocalFile
import io.skjaere.debridav.fs.databasefs.DbDirectory
import io.skjaere.debridav.fs.databasefs.DbFile
import io.skjaere.debridav.fs.databasefs.DbItem
import io.skjaere.debridav.fs.databasefs.DebridTorrentContentsDTO
import io.skjaere.debridav.fs.databasefs.DebridUsenetContentsDTO
import io.skjaere.debridav.fs.databasefs.LocalFile
import jakarta.transaction.Transactional
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class DbItemToDebridFsItemConverter(
    private val debridFileContentsDTOConverter: DTOToDebridFileContentsConverter
) : Converter<DbItem, DebridFsItem> {

    @Transactional
    override fun convert(source: DbItem): DebridFsItem? {
        return when (source) {
            is DbFile -> DebridFsFile(
                id = source.id,
                name = source.name!!,
                size = source.size!!,
                lastModified = source.lastModified!!,
                path = source.path!!,
                contents = when (source.contents) {
                    is DebridTorrentContentsDTO ->
                        debridFileContentsDTOConverter.convert(source.contents as DebridTorrentContentsDTO)

                    is DebridUsenetContentsDTO ->
                        debridFileContentsDTOConverter.convert(source.contents as DebridUsenetContentsDTO)

                    else -> throw IllegalArgumentException("unknown item type")
                }
            )

            is LocalFile -> DebridFsLocalFile(
                id = source.id,
                name = source.name!!,
                size = source.size!!,
                lastModified = source.lastModified!!,
                path = source.path!!,
                contents = source.contents!!,
                mimeType = source.mimeType
            )

            is DbDirectory -> DebridFsDirectory(
                id = source.id,
                name = source.name ?: "",
                path = source.path!!,
                lastModified = source.lastModified!!,
                children = source.children?.map {
                    DebridFsDirectory(
                        id = source.id,
                        name = it.name!!,
                        path = it.path!!,
                        lastModified = it.lastModified!!,
                        children = emptyList()
                    )
                } ?: emptyList()
            )

            else -> throw IllegalArgumentException("unknown item type ${source.javaClass}")
        }
    }
}
