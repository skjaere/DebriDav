package io.skjaere.debridav.debrid.client.model

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile

sealed interface GetCachedFilesResponse {
    val debridProvider: DebridProvider
    fun getCachedFiles(): List<CachedFile> {
        return when (this) {
            is SuccessfulGetCachedFilesResponse -> this.successfullyCachedFiles
            else -> listOf()
        }
    }
}

@Suppress("EmptyClassBlock")
data class ClientErrorGetCachedFilesResponse(override val debridProvider: DebridProvider) : GetCachedFilesResponse
data class NetworkErrorGetCachedFilesResponse(override val debridProvider: DebridProvider) : GetCachedFilesResponse
data class NotCachedGetCachedFilesResponse(override val debridProvider: DebridProvider) : GetCachedFilesResponse
data class ProviderErrorGetCachedFilesResponse(override val debridProvider: DebridProvider) : GetCachedFilesResponse
data class SuccessfulGetCachedFilesResponse(
    val successfullyCachedFiles: List<CachedFile>,
    override val debridProvider: DebridProvider
) : GetCachedFilesResponse
