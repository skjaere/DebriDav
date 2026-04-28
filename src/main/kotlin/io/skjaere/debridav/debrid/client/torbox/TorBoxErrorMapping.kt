package io.skjaere.debridav.debrid.client.torbox

import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridError
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.DebridQuotaExceededError
import io.skjaere.debridav.debrid.model.UnknownDebridError
import kotlinx.serialization.Serializable

/**
 * The error envelope TorBox returns alongside non-2xx responses. The same `success` /
 * `error` / `detail` fields appear on success responses too — they're just inverted.
 *
 * `data` is intentionally omitted; on error responses it's null.
 */
@Serializable
data class TorBoxErrorBody(
    val success: Boolean = false,
    val error: String? = null,
    val detail: String? = null,
)

/**
 * Maps TorBox error responses to [DebridError] subtypes. Codes are sourced from TorBox's
 * support docs and the AIOStreams issue #582 thread (which surfaced the body-level limit
 * codes during a feature request to handle them).
 */
object TorBoxErrorMapping {

    /**
     * Body-level `error` strings that mean "the account has hit some availability budget."
     * All three resolve on a wait-then-retry pattern — long for MONTHLY, shorter for
     * COOLDOWN/ACTIVE — but all warrant `waitAfterQuotaExceeded` rather than per-tick retry.
     */
    private val QUOTA_EXCEEDED_CODES = setOf(
        "MONTHLY_LIMIT",   // monthly bandwidth quota exhausted
        "COOLDOWN_LIMIT",  // post-bandwidth-spike cooldown
        "ACTIVE_LIMIT",    // concurrent download slot exhausted
    )

    @Suppress("MagicNumber")
    fun toDebridError(
        statusCode: Int,
        body: TorBoxErrorBody?,
        endpoint: String,
    ): DebridError {
        val errorString = body?.error
        val detail = body?.detail
        val message = "torbox error: error=$errorString detail=$detail"

        if (errorString in QUOTA_EXCEEDED_CODES) {
            return DebridQuotaExceededError(
                message, statusCode, endpoint,
                reason = errorString ?: "quota_exceeded",
            )
        }

        return when (statusCode) {
            in 400..499 -> DebridClientError(message, statusCode, endpoint)
            in 500..599 -> DebridProviderError(message, statusCode, endpoint)
            else -> UnknownDebridError(message, statusCode, endpoint)
        }
    }
}
