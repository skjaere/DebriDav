package io.skjaere.debridav

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Forwards the SPA's client-side routes to `/index.html` so React Router can
 * handle them on page load / hard refresh. Without this, deep links like
 * `/config/health-check` return 404 because Spring only serves `/index.html`
 * at the root and no controller matches the path.
 *
 * Keep in sync with the frontend router in `debridav-frontend/src/router/index.tsx`.
 */
@Configuration
class FrontendRoutingConfiguration : WebMvcConfigurer {
    override fun addViewControllers(registry: ViewControllerRegistry) {
        val topLevelRoutes = listOf(
            "/login",
            "/files",
            "/usenet",
            "/torrents",
            "/health",
            "/logs"
        )
        topLevelRoutes.forEach { path ->
            registry.addViewController(path).setViewName("forward:/index.html")
        }
        registry.addViewController("/config/{*rest}").setViewName("forward:/index.html")
    }
}
