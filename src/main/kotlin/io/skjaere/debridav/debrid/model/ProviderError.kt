package io.skjaere.debridav.debrid.model

import kotlinx.serialization.Serializable


@Serializable
data class ProviderError(override val provider: DebridProvider, override val lastChecked: Long) : DebridFile {
    override val status: DebridFileType
        get() = DebridFileType.PROVIDER_ERROR
}
