package io.skjaere.debridav.test

import io.skjaere.debridav.debrid.client.torbox.TorBoxErrorBody
import io.skjaere.debridav.debrid.client.torbox.TorBoxErrorMapping
import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridError
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.DebridQuotaExceededError
import io.skjaere.debridav.debrid.model.UnknownDebridError
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers TorBox's documented body-level error strings (sourced from TorBox's API docs and
 * AIOStreams issue #582 which surfaced these codes during a feature request to handle them):
 *
 *   {"success":false, "error":"<CODE>", "detail":"<text>", "data":null}
 *
 * Quota-style codes go to DebridQuotaExceededError so the embedded gate trips for
 * waitAfterQuotaExceeded; everything else falls through to the status-based mapping.
 */
class TorBoxErrorMappingTest {

    @ParameterizedTest(name = "error={0} status={1} → {2}")
    @MethodSource("codeMappingCases")
    fun `TorBox body error string maps to expected DebridError`(
        errorString: String?,
        statusCode: Int,
        expected: KClass<out DebridError>,
    ) {
        val body = TorBoxErrorBody(success = false, error = errorString, detail = "stub")
        val mapped = TorBoxErrorMapping.toDebridError(
            statusCode = statusCode,
            body = body,
            endpoint = "/api/torrents/createtorrent",
        )
        assertEquals(expected, mapped::class)
    }

    @Test
    fun `quota error preserves the raw error string as reason`() {
        val mapped = TorBoxErrorMapping.toDebridError(
            statusCode = 403,
            body = TorBoxErrorBody(success = false, error = "MONTHLY_LIMIT", detail = "x"),
            endpoint = "/x",
        )
        val quota = assertIs<DebridQuotaExceededError>(mapped)
        assertEquals("MONTHLY_LIMIT", quota.reason)
    }

    @Test
    fun `null body falls through to status-based mapping`() {
        // Malformed / missing body shouldn't break — degrade to generic status routing.
        assertIs<DebridClientError>(
            TorBoxErrorMapping.toDebridError(403, null, "/x"),
        )
        assertIs<DebridProviderError>(
            TorBoxErrorMapping.toDebridError(503, null, "/x"),
        )
    }

    @Test
    fun `unknown error string falls through to status-based mapping`() {
        // Future-added TorBox code we haven't classified — must not be silently bucketed
        // into quota.
        val mapped = TorBoxErrorMapping.toDebridError(
            statusCode = 503,
            body = TorBoxErrorBody(success = false, error = "FUTURE_NEW_CODE", detail = "x"),
            endpoint = "/x",
        )
        assertIs<DebridProviderError>(mapped)
    }

    @Test
    fun `mapping preserves endpoint and statusCode on the produced error`() {
        val mapped = TorBoxErrorMapping.toDebridError(
            statusCode = 403,
            body = TorBoxErrorBody(success = false, error = "ACTIVE_LIMIT", detail = "x"),
            endpoint = "/api/torrents/createtorrent",
        )
        assertEquals(403, mapped.statusCode)
        assertEquals("/api/torrents/createtorrent", mapped.endpoint)
        assertIs<DebridQuotaExceededError>(mapped)
    }

    companion object {
        @JvmStatic
        fun codeMappingCases(): List<Arguments> = listOf(
            // Quota / limit codes (the documented set from TorBox)
            Arguments.of("MONTHLY_LIMIT", 403, DebridQuotaExceededError::class),
            Arguments.of("COOLDOWN_LIMIT", 403, DebridQuotaExceededError::class),
            Arguments.of("ACTIVE_LIMIT", 403, DebridQuotaExceededError::class),

            // Quota codes should bucket regardless of which HTTP status TorBox wraps them in
            Arguments.of("MONTHLY_LIMIT", 429, DebridQuotaExceededError::class),
            Arguments.of("ACTIVE_LIMIT", 503, DebridQuotaExceededError::class),

            // Auth / state-style errors fall through to status-based mapping
            Arguments.of(null, 403, DebridClientError::class),
            Arguments.of("INVALID_AUTH", 403, DebridClientError::class),
            Arguments.of("BAD_REQUEST", 400, DebridClientError::class),

            // Server-side failures
            Arguments.of(null, 500, DebridProviderError::class),
            Arguments.of("INTERNAL", 500, DebridProviderError::class),
            Arguments.of(null, 503, DebridProviderError::class),

            // HTTP 429 generic rate limit — short-term transient, not quota
            Arguments.of(null, 429, DebridClientError::class),

            // Unknown status range
            Arguments.of(null, 0, UnknownDebridError::class),
        )
    }
}
