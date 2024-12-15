package io.skjaere.debridav.fs.databasefs.converters

import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.ClientError
import io.skjaere.debridav.debrid.model.DebridFile
import io.skjaere.debridav.debrid.model.MissingFile
import io.skjaere.debridav.debrid.model.NetworkError
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.fs.databasefs.CachedFileDTO
import io.skjaere.debridav.fs.databasefs.ClientErrorDTO
import io.skjaere.debridav.fs.databasefs.DebridFileDTO
import io.skjaere.debridav.fs.databasefs.MissingFileDTO
import io.skjaere.debridav.fs.databasefs.NetworkErrorDTO
import io.skjaere.debridav.fs.databasefs.ProviderErrorDTO
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class DTOTODebridLinkConverter : Converter<DebridFileDTO, DebridFile> {
    override fun convert(source: DebridFileDTO): DebridFile {
        return when (source) {
            is CachedFileDTO -> {
                CachedFile(
                    path = source.path!!,
                    size = source.size!!,
                    mimeType = source.mimeType!!,
                    link = source.link!!,
                    provider = DebridProvider.valueOf(source.provider!!),
                    lastChecked = source.lastChecked!!,
                    params = source.params!!
                )
            }

            is MissingFileDTO -> {
                MissingFile(DebridProvider.valueOf(source.provider!!), source.lastChecked!!)
            }

            is ProviderErrorDTO -> {
                ProviderError(DebridProvider.valueOf(source.provider!!), source.lastChecked!!)
            }

            is ClientErrorDTO -> {
                ClientError(DebridProvider.valueOf(source.provider!!), source.lastChecked!!)
            }

            is NetworkErrorDTO -> {
                NetworkError(DebridProvider.valueOf(source.provider!!), source.lastChecked!!)
            }

            else -> throw IllegalArgumentException("unknown debrid-file type: ${source.javaClass}")
        }
    }
}
