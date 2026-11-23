package io.skjaere.debridav.config.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.firewall.FirewalledRequest
import org.springframework.security.web.firewall.HttpFirewall
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.AnyRequestMatcher

@Configuration
@EnableWebSecurity
class SecurityConfiguration(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val sidCookieAuthenticationFilter: SidCookieAuthenticationFilter,
) {
    @Bean
    @Order(1)
    fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/**", "/actuator/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                // qBittorrent answers 403 when there is no valid session, and Sonarr/Radarr
                // depend on it: they log in again only after a 403, and their connection
                // test (webapiVersion without a cookie) accepts only 200, 403 or 404.
                it.defaultAuthenticationEntryPointFor(
                    forbiddenEntryPoint(),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/v2/**"),
                )
                it.defaultAuthenticationEntryPointFor(unauthorizedEntryPoint(), AnyRequestMatcher.INSTANCE)
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            // SID-cookie filter is scoped (via shouldNotFilter) to /api/v2/** only, so the
            // qBittorrent-emulation cookie can authenticate Sonarr / Radarr while the
            // frontend's own login flow stays Bearer-only.
            .addFilterAfter(sidCookieAuthenticationFilter, JwtAuthenticationFilter::class.java)
            .authorizeHttpRequests { auth ->
                // Auth endpoints — callers don't have a token yet, so they can't be authed.
                auth.requestMatchers("/api/v1/auth/**").permitAll()
                // qBittorrent emulation login — Sonarr / Radarr hit this *before* they have
                // a SID cookie. Fail-closed semantics live in the controller itself when
                // WebDAV creds are blank.
                auth.requestMatchers("/api/v2/auth/**").permitAll()
                // Stream tokens — opaque JWT carried in the URL path. Public by design so
                // VLC / mpv / Plex can fetch without an Authorization header.
                auth.requestMatchers("/api/v1/stream/**").permitAll()
                // Collection play sessions — addressed by the session's own random token, which only
                // the user who started the play gets: FFmpeg, ffprobe, Safari's HLS player and the
                // browser's EventSource can't send an Authorization header. Starting a play is authed.
                auth.requestMatchers("/api/v1/collection/sessions/**").permitAll()
                // SABnzbd emulation — authenticates via its native `apikey=` query param,
                // validated inside the controller. Spring just lets the request through.
                auth.requestMatchers("/api").permitAll()

                // Public actuator endpoints — health probes and the Prometheus scrape need
                // to be reachable without creds.
                auth.requestMatchers("/actuator/health/**").permitAll()
                auth.requestMatchers("/actuator/prometheus").permitAll()

                // Everything else under /api/** and /actuator/** requires auth. There are
                // no per-endpoint bypass toggles — adding a new endpoint defaults it to
                // authenticated unless it's explicitly listed above.
                auth.anyRequest().authenticated()
            }

        return http.build()
    }

    @Bean
    @Order(2)
    fun webDavSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        // WebDAV authentication is handled by Milton's own basic/digest flow in
        // AbstractResource.authenticate, which keys off whether webdav-username/password
        // are configured. Spring just delegates — no JWT, no filter rules.
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }

        return http.build()
    }

    @Bean
    fun httpFirewall(): HttpFirewall = WebDavAwareFirewall(
        strict = StrictHttpFirewall().apply {
            // Allow WebDAV methods (PROPFIND, MKCOL, COPY, MOVE, LOCK, UNLOCK, PROPPATCH)
            setUnsafeAllowAnyHttpMethod(true)
        },
        // File and directory names may contain % and ; ("100% Wolf (2020)"), which clients send
        // as %25, and as ; or %3B. The strict defaults reject both, so such a file would be listed
        // but return 400 on every GET, PROPFIND and MOVE. Allowed under /webdav/ only: elsewhere
        // these characters have been used to slip past security rules. Path traversal (.., //)
        // stays rejected. A backslash (%5C) is still refused, by Tomcat before this runs.
        webDav = StrictHttpFirewall().apply {
            setUnsafeAllowAnyHttpMethod(true)
            setAllowUrlEncodedPercent(true)
            setAllowSemicolon(true)
        },
    )

    /** Applies [webDav] to requests under `/webdav/` and [strict] to everything else. */
    private class WebDavAwareFirewall(
        private val strict: HttpFirewall,
        private val webDav: HttpFirewall,
    ) : HttpFirewall {
        override fun getFirewalledRequest(request: HttpServletRequest): FirewalledRequest =
            (if (request.requestURI.startsWith(WEBDAV_PREFIX)) webDav else strict).getFirewalledRequest(request)

        override fun getFirewalledResponse(response: HttpServletResponse): HttpServletResponse =
            strict.getFirewalledResponse(response)

        private companion object {
            const val WEBDAV_PREFIX = "/webdav/"
        }
    }

    private fun forbiddenEntryPoint() = AuthenticationEntryPoint {
            _: HttpServletRequest, response: HttpServletResponse, _: AuthenticationException ->
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = "text/plain"
        response.writer.write("Forbidden")
    }

    private fun unauthorizedEntryPoint() = AuthenticationEntryPoint {
            _: HttpServletRequest, response: HttpServletResponse, _: AuthenticationException ->
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = "application/json"
        response.writer.write("""{"error":"Unauthorized"}""")
    }
}
