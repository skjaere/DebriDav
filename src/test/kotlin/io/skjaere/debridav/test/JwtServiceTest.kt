package io.skjaere.debridav.test

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.skjaere.debridav.config.auth.AuthConfigurationProperties
import io.skjaere.debridav.config.auth.JwtService
import org.junit.jupiter.api.Test
import java.util.Date
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwtServiceTest {

    private val secret = "test-secret-key-that-is-at-least-256-bits-long-for-hs256"
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())
    private val jwtService = JwtService(AuthConfigurationProperties().apply { jwtSecret = secret })

    @Test
    fun `login token is accepted as a login`() {
        val token = jwtService.generateToken("admin")

        assertEquals("admin", jwtService.validateTokenAndGetUsername(token))
    }

    @Test
    fun `login token carries the login type`() {
        val token = jwtService.generateToken("admin")

        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        assertEquals("login", claims["type"])
    }

    @Test
    fun `stream token is not accepted as a login`() {
        // A stream URL (/api/v1/stream/t/<token>) ends up in player histories, proxy logs
        // and shared links. Its token must only ever open that one file, never the API.
        val token = jwtService.generateStreamToken("/downloads/Some.Movie.2024.mkv")

        assertNull(jwtService.validateTokenAndGetUsername(token))
    }

    @Test
    fun `stream token is still accepted by the stream endpoint`() {
        val token = jwtService.generateStreamToken("/downloads/Some.Movie.2024.mkv")

        assertEquals("/downloads/Some.Movie.2024.mkv", jwtService.validateStreamToken(token))
    }

    @Test
    fun `login token is not accepted as a stream token`() {
        val token = jwtService.generateToken("admin")

        assertNull(jwtService.validateStreamToken(token))
    }

    @Test
    fun `login token issued before login tokens were typed is still accepted`() {
        // Tokens minted before the type claim existed have no `type`; every one of them
        // was a login token (stream tokens have carried type=stream since they were
        // introduced), so sessions open across the upgrade keep working until they expire.
        val token = signed(subject = "admin", type = null)

        assertEquals("admin", jwtService.validateTokenAndGetUsername(token))
    }

    @Test
    fun `stream token issued before login tokens were typed is not accepted as a login`() {
        val token = signed(subject = "/downloads/Some.Movie.2024.mkv", type = "stream")

        assertNull(jwtService.validateTokenAndGetUsername(token))
    }

    @Test
    fun `token of any other type is not accepted as a login`() {
        val token = signed(subject = "admin", type = "something-else")

        assertNull(jwtService.validateTokenAndGetUsername(token))
    }

    @Test
    fun `login token signed with another key is not accepted`() {
        val otherKey = Keys.hmacShaKeyFor("another-secret-key-that-is-at-least-256-bits-long".toByteArray())
        val token = signed(subject = "admin", type = "login", signingKey = otherKey)

        assertNull(jwtService.validateTokenAndGetUsername(token))
    }

    @Suppress("MagicNumber")
    private fun signed(
        subject: String,
        type: String?,
        signingKey: SecretKey = key,
    ): String {
        val now = Date()
        val builder = Jwts.builder()
            .subject(subject)
            .issuedAt(now)
            .expiration(Date(now.time + 3600 * 1000))
        if (type != null) builder.claim("type", type)
        return builder.signWith(signingKey).compact()
    }
}
