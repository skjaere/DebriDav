package io.skjaere.debridav.config.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val SID_COOKIE = "SID"
private const val QBITTORRENT_API_PREFIX = "/api/v2/"

// Populates the SecurityContext from the SID cookie set by the qBittorrent
// /api/v2/auth/login endpoint. Scoped to /api/v2/ via shouldNotFilter so a
// stale browser cookie can't leak authentication into the frontend's own
// login flow (/api/v1/auth). qBittorrent clients hold their session as a
// cookie rather than as an Authorization header, so they need this filter
// to authenticate against /api/v2/** (which now requires auth unconditionally).
@Component
class SidCookieAuthenticationFilter(
    private val jwtService: JwtService
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(QBITTORRENT_API_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (SecurityContextHolder.getContext().authentication == null) {
            val token = request.cookies
                ?.firstOrNull { it.name == SID_COOKIE }
                ?.value
                ?.takeIf { it.isNotBlank() }
            if (token != null) {
                val username = jwtService.validateTokenAndGetUsername(token)
                if (username != null) {
                    val auth = UsernamePasswordAuthenticationToken(username, null, emptyList())
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}
