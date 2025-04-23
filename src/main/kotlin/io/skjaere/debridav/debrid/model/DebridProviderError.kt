package io.skjaere.debridav.debrid.model

import io.skjaere.debridav.debrid.DebridProvider

@Suppress("EmptyClassBlock")
sealed class DebridError(
    message: String,
    val statusCode: Int,
    val endpoint: String,
    val provider: DebridProvider? = null,
) : RuntimeException(message) {}

@Suppress("UnusedPrivateProperty")
class DebridProviderError(
    message: String,
    statusCode: Int,
    endpoint: String,
) : DebridError(message, statusCode, endpoint)

@Suppress("UnusedPrivateProperty")
class DebridClientError(
    message: String,
    statusCode: Int,
    endpoint: String
) : DebridError(message, statusCode, endpoint)

@Suppress("UnusedPrivateProperty")
class UnknownDebridError(
    message: String,
    statusCode: Int,
    endpoint: String
) : DebridError(message, statusCode, endpoint)
