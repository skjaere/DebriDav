package io.skjaere.debridav.test

import io.skjaere.debridav.debrid.client.realdebrid.RealDebridErrorMapping
import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridError
import io.skjaere.debridav.debrid.model.DebridFileUnavailableError
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
import kotlin.test.assertTrue

/**
 * Covers every error_code from RD's published error-code table that can plausibly land on
 * the unrestrict path. The mapping is the source of truth for how a non-2xx unrestrict
 * response gets translated into the persisted DebridFile state via DebridLinkService.
 */
class RealDebridErrorMappingTest {

    @ParameterizedTest(name = "error_code={0} ({1}) → {2}")
    @MethodSource("codeMappingCases")
    fun `RD error_code maps to expected DebridError subtype`(
        errorCode: Int,
        @Suppress("UNUSED_PARAMETER") description: String,
        expected: KClass<out DebridError>,
    ) {
        // Status 503 is the most common envelope RD wraps these codes in; the mapping should
        // be code-driven, not status-driven, when the code is in one of the named buckets.
        val mapped = RealDebridErrorMapping.toDebridError(
            statusCode = 503,
            error = description,
            errorCode = errorCode,
            endpoint = "/unrestrict/link",
        )
        assertEquals(expected, mapped::class)
    }

    @Test
    fun `quota error preserves the human-readable reason`() {
        val mapped = RealDebridErrorMapping.toDebridError(
            statusCode = 503,
            error = "hoster_unavailable",
            errorCode = 19,
            endpoint = "/unrestrict/link",
        )
        val quota = assertIs<DebridQuotaExceededError>(mapped)
        assertEquals("hoster_unavailable", quota.reason)
    }

    @Test
    fun `file-unavailable error preserves the reason for telemetry`() {
        val mapped = RealDebridErrorMapping.toDebridError(
            statusCode = 503,
            error = "infringing_file",
            errorCode = 35,
            endpoint = "/unrestrict/link",
        )
        val unavail = assertIs<DebridFileUnavailableError>(mapped)
        assertEquals("infringing_file", unavail.reason)
    }

    @Test
    fun `null error_code falls through to status-based mapping`() {
        // Mid-flight network blip with no JSON body parsed: errorCode is null, status defines it.
        assertIs<DebridClientError>(
            RealDebridErrorMapping.toDebridError(404, null, null, "/x"),
        )
        assertIs<DebridProviderError>(
            RealDebridErrorMapping.toDebridError(502, null, null, "/x"),
        )
        assertIs<UnknownDebridError>(
            RealDebridErrorMapping.toDebridError(0, null, null, "/x"),
        )
    }

    @Test
    fun `unknown error_code falls through to status-based mapping`() {
        // Code RD might add later that we haven't classified — must not be silently bucketed.
        val mapped = RealDebridErrorMapping.toDebridError(
            statusCode = 503,
            error = "future_code",
            errorCode = 999,
            endpoint = "/unrestrict/link",
        )
        assertIs<DebridProviderError>(mapped)
    }

    @Test
    fun `mapping preserves endpoint and statusCode on the produced error`() {
        val mapped = RealDebridErrorMapping.toDebridError(
            statusCode = 503, error = "x", errorCode = 19, endpoint = "/unrestrict/link",
        )
        assertEquals(503, mapped.statusCode)
        assertEquals("/unrestrict/link", mapped.endpoint)
        assertTrue(mapped.message?.contains("error_code=19") == true)
    }

    companion object {
        @JvmStatic
        fun codeMappingCases(): List<Arguments> = listOf(
            // Quota / hoster availability — long backoff
            Arguments.of(17, "Hoster in maintenance", DebridQuotaExceededError::class),
            Arguments.of(18, "Hoster limit reached", DebridQuotaExceededError::class),
            Arguments.of(19, "Hoster temporarily unavailable", DebridQuotaExceededError::class),
            Arguments.of(23, "Traffic exhausted", DebridQuotaExceededError::class),
            Arguments.of(36, "Fair Usage Limit", DebridQuotaExceededError::class),

            // File specifically gone — MissingFile semantically
            Arguments.of(7, "Resource not found", DebridFileUnavailableError::class),
            Arguments.of(16, "Unsupported hoster", DebridFileUnavailableError::class),
            Arguments.of(24, "File unavailable", DebridFileUnavailableError::class),
            Arguments.of(28, "File not allowed", DebridFileUnavailableError::class),
            Arguments.of(35, "Infringing file", DebridFileUnavailableError::class),

            // Account-state issues — operator must intervene
            Arguments.of(14, "Account locked", DebridClientError::class),
            Arguments.of(20, "Hoster not available for free users", DebridClientError::class),
            Arguments.of(22, "IP Address not allowed", DebridClientError::class),

            // Transient provider errors — short retry via existing 5xx fallthrough
            Arguments.of(5, "Slow down", DebridProviderError::class),
            Arguments.of(6, "Resource unreachable", DebridProviderError::class),
            Arguments.of(25, "Service unavailable", DebridProviderError::class),
            Arguments.of(34, "Too many requests", DebridProviderError::class),
        )
    }
}
