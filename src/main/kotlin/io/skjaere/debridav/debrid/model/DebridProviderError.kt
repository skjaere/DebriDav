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

class DebridQuotaExceededError(
    message: String,
    statusCode: Int,
    endpoint: String,
    val reason: String? = null,
) : DebridError(message, statusCode, endpoint)

/** The provider acknowledges the request but the specific file can't be served — DMCA
 *  takedown, hoster removed it, unsupported hoster, etc. Semantically MissingFile. */
class DebridFileUnavailableError(
    message: String,
    statusCode: Int,
    endpoint: String,
    val reason: String? = null,
) : DebridError(message, statusCode, endpoint)

