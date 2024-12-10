package io.skjaere.debridav.debrid.model

import io.skjaere.debridav.fs.DebridProvider
import kotlinx.serialization.Serializable

@Serializable
sealed interface DebridFile {
    val provider: DebridProvider
    val lastChecked: Long
    val status: DebridFileType
}

enum class DebridFileType {
    CACHED, MISSING, PROVIDER_ERROR, NETWORK_ERROR, CLIENT_ERROR
}
