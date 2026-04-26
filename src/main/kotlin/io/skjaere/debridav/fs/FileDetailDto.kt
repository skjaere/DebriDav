package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.DebridProvider

data class FileDetailDto(
    val name: String,
    val path: String,
    val size: Long?,
    val lastModified: Long?,
    val mimeType: String?,
    val fileType: FileType,
    val hash: String?,
    val providerStatus: List<ProviderStatusDto>?
)

enum class FileType {
    TORRENT, USENET_RELEASE, NZB, LOCAL
}

data class ProviderStatusDto(
    val provider: DebridProvider,
    val status: ProviderCacheStatus,
    val lastChecked: Long?
)

enum class ProviderCacheStatus {
    CACHED, MISSING, PROVIDER_ERROR, CLIENT_ERROR, NETWORK_ERROR, UNKNOWN_ERROR
}
