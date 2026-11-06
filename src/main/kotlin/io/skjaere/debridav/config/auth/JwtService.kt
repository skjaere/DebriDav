package io.skjaere.debridav.config.auth

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.Date
import javax.crypto.SecretKey

private const val MIN_HS256_KEY_BYTES = 32

@Service
class JwtService(
    private val authConfig: AuthConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(JwtService::class.java)

    @PostConstruct
    fun validateSecret() {
        val secret = authConfig.jwtSecret
        if (secret.isNotBlank() && secret.toByteArray().size < MIN_HS256_KEY_BYTES) {
            error(
                "DEBRIDAV_AUTH_JWT_SECRET must be at least $MIN_HS256_KEY_BYTES bytes for HS256 " +
                    "(got ${secret.toByteArray().size}). Generate one with: openssl rand -base64 48"
            )
        }
    }

    private val key: SecretKey by lazy {
        if (authConfig.jwtSecret.isBlank()) {
            logger.warn(
                "DEBRIDAV_AUTH_JWT-SECRET is not set; generating a random key for this session. " +
                        "Tokens will be invalidated on restart — set a stable secret if that matters."
            )
            Jwts.SIG.HS256.key().build()
        } else {
            Keys.hmacShaKeyFor(authConfig.jwtSecret.toByteArray())
        }
    }

    @Suppress("MagicNumber")
    fun generateToken(username: String): String {
        val now = Date()
        val expiration = Date(now.time + authConfig.tokenExpirationHours * 3600 * 1000)

        return Jwts.builder()
            .subject(username)
            .claim(TYPE_CLAIM, LOGIN_TYPE)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(key)
            .compact()
    }

    // Only a login token authenticates a caller. Stream tokens are signed with the same key
    // and travel in URLs (player histories, proxy logs, shared links), so a token of any
    // other type must never pass as a login. Login tokens issued before the type claim
    // existed carry no type and are still accepted, so sessions survive the upgrade. That
    // reopens nothing: stream tokens have carried type=stream since they were introduced,
    // so an untyped token can only be an old login token, and those expire on their own.
    fun validateTokenAndGetUsername(token: String): String? = try {
        val claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
        when (claims[TYPE_CLAIM]) {
            LOGIN_TYPE, null -> claims.subject
            else -> null
        }
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @Suppress("MagicNumber")
    fun generateStreamToken(path: String): String {
        val now = Date()
        val expiration = Date(now.time + STREAM_TOKEN_EXPIRY_SECONDS * 1000)

        return Jwts.builder()
            .subject(path)
            .claim(TYPE_CLAIM, STREAM_TYPE)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(key)
            .compact()
    }

    fun validateStreamToken(token: String): String? = try {
        val claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
        if (claims[TYPE_CLAIM] == STREAM_TYPE) claims.subject else null
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val STREAM_TOKEN_EXPIRY_SECONDS = 86400L // 24 hours
        private const val TYPE_CLAIM = "type"
        private const val LOGIN_TYPE = "login"
        private const val STREAM_TYPE = "stream"
    }
}
