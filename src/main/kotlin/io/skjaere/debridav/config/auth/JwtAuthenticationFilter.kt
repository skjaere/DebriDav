package io.skjaere.debridav.config.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val BEARER_PREFIX = "Bearer "
private const val SID_COOKIE = "SID"

@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = extractToken(request)
        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            val username = jwtService.validateTokenAndGetUsername(token)
            if (username != null) {
                val auth = UsernamePasswordAuthenticationToken(username, null, emptyList())
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }

    /**
     * Prefer the `Authorization: Bearer …` header (used by debridav's own clients).
     * Fall back to the `SID` cookie set by the qBittorrent-emulation login endpoint
     * — qBittorrent clients hold the session as a cookie rather than a header.
     */
    private fun extractToken(request: HttpServletRequest): String? {
        val authHeader = request.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length)
        }
        return request.cookies
            ?.firstOrNull { it.name == SID_COOKIE }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }
}
