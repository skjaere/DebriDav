package io.skjaere.debridav.debrid.model

@Suppress("EmptyClassBlock")
sealed class DebridError(
    message: String,
    val statusCode: Int,
    val endpoint: String
): RuntimeException(message) {}

@Suppress("UnusedPrivateProperty")
class DebridProviderError(
    message: String,
    statusCode: Int,
    endpoint: String
) : DebridError(message, statusCode, endpoint)

@Suppress("UnusedPrivateProperty")
class DebridClientError(
    message: String,
    statusCode: Int,
    endpoint: String
) : DebridError(message,statusCode, endpoint)

@Suppress("UnusedPrivateProperty")
class UnknownDebridError(
    message: String,
    statusCode: Int,
    endpoint: String
) : DebridError(message, statusCode, endpoint)
