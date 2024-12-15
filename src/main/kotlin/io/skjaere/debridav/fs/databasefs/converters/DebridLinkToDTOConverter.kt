package io.skjaere.debridav.fs.databasefs.converters

import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.ClientError
import io.skjaere.debridav.debrid.model.DebridFile
import io.skjaere.debridav.debrid.model.MissingFile
import io.skjaere.debridav.debrid.model.NetworkError
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.debrid.model.UnknownError
import io.skjaere.debridav.fs.databasefs.CachedFileDTO
import io.skjaere.debridav.fs.databasefs.ClientErrorDTO
import io.skjaere.debridav.fs.databasefs.DebridFileDTO
import io.skjaere.debridav.fs.databasefs.MissingFileDTO
import io.skjaere.debridav.fs.databasefs.NetworkErrorDTO
import io.skjaere.debridav.fs.databasefs.ProviderErrorDTO
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class DebridLinkToDTOConverter : Converter<DebridFile, DebridFileDTO> {
    override fun convert(source: DebridFile): DebridFileDTO? {
        return when (source) {
            is CachedFile -> {
                val cachedFile = CachedFileDTO()
                cachedFile.path = source.path
                cachedFile.size = source.size
                cachedFile.size = source.size
                cachedFile.params = source.params
                cachedFile.link = source.link
                cachedFile.mimeType = source.mimeType
                cachedFile.provider = source.provider.toString()
                cachedFile.lastChecked = source.lastChecked
                cachedFile
            }

            is ClientError -> {
                val clientError = ClientErrorDTO()
                clientError.provider = source.provider.toString()
                clientError.lastChecked = source.lastChecked
                clientError
            }

            is MissingFile -> {
                val missingFileDTO = MissingFileDTO()
                missingFileDTO.provider = source.provider.toString()
                missingFileDTO.lastChecked = source.lastChecked
                missingFileDTO
            }

            is NetworkError -> {
                val networkErrorDTO = NetworkErrorDTO()
                networkErrorDTO.provider = source.provider.toString()
                networkErrorDTO.lastChecked = source.lastChecked
                networkErrorDTO
            }

            is ProviderError -> {
                val providerErrorDTO = ProviderErrorDTO()
                providerErrorDTO.provider = source.provider.toString()
                providerErrorDTO.lastChecked = source.lastChecked
                providerErrorDTO
            }

            is UnknownError -> TODO()
        }
    }
}
