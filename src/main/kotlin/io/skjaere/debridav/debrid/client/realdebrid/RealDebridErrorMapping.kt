package io.skjaere.debridav.debrid.client.realdebrid

import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridError
import io.skjaere.debridav.debrid.model.DebridFileUnavailableError
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.DebridQuotaExceededError
import io.skjaere.debridav.debrid.model.UnknownDebridError

/**
 * Maps a Real-Debrid error response (HTTP status + parsed `error` + `error_code`) to the
 * appropriate [DebridError] subtype.
 *
 * Codes are sourced from RD's published error-code table at api.real-debrid.com. Codes
 * that don't appear in any of the buckets below fall through to a status-code-based
 * mapping (4xx → Client, 5xx → Provider, other → Unknown).
 */
@Suppress("MagicNumber")
object RealDebridErrorMapping {

    /** Account/hoster availability budget exhausted — long backoff (waitAfterQuotaExceeded). */
    private val QUOTA_EXCEEDED_CODES = setOf(
        17, // Hoster in maintenance
        18, // Hoster limit reached
        19, // Hoster temporarily unavailable
        23, // Traffic exhausted
        36, // Fair Usage Limit
    )

    /** This specific file can't be redeemed and won't recover — MissingFile semantically. */
    private val FILE_UNAVAILABLE_CODES = setOf(
        7,  // Resource not found
        16, // Unsupported hoster
        24, // File unavailable
        28, // File not allowed
        35, // Infringing file (DMCA)
    )

    /** Account-state issues that won't resolve without operator intervention — ClientError. */
    private val CLIENT_ERROR_CODES = setOf(
        14, // Account locked
        20, // Hoster not available for free users
        22, // IP Address not allowed
    )

    fun toDebridError(
        statusCode: Int,
        error: String?,
        errorCode: Int?,
        endpoint: String,
    ): DebridError {
        val message = "real-debrid error: error=$error error_code=$errorCode"
        val reason = error ?: errorCode?.let { "error_code=$it" }

        when (errorCode) {
            in QUOTA_EXCEEDED_CODES -> return DebridQuotaExceededError(message, statusCode, endpoint, reason)
            in FILE_UNAVAILABLE_CODES -> return DebridFileUnavailableError(message, statusCode, endpoint, reason)
            in CLIENT_ERROR_CODES -> return DebridClientError(message, statusCode, endpoint)
        }

        return when (statusCode) {
            in 400..499 -> DebridClientError(message, statusCode, endpoint)
            in 500..599 -> DebridProviderError(message, statusCode, endpoint)
            else -> UnknownDebridError(message, statusCode, endpoint)
        }
    }
}
