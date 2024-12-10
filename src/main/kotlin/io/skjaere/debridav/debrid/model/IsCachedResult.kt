package io.skjaere.debridav.debrid.model

import io.skjaere.debridav.fs.DebridProvider

sealed interface IsCachedResult {
    val debridProvider: DebridProvider
}

data class SuccessfulIsCachedResult(
    val isCached: Boolean,
    override val debridProvider: DebridProvider
) : IsCachedResult

data class ProviderErrorIsCachedResponse(
    val error: Exception,
    override val debridProvider: DebridProvider
) : IsCachedResult

data class ClientErrorIsCachedResponse(
    val error: Exception,
    override val debridProvider: DebridProvider
) : IsCachedResult

data class GeneralErrorIsCachedResponse(
    val error: Exception,
    override val debridProvider: DebridProvider
) : IsCachedResult
