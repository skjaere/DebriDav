package io.skjaere.debridav.test.config

import io.skjaere.debridav.FrontendRoutingConfiguration
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

/**
 * A link into the app, or a reload of one of its pages, serves the app (index.html), whose router then shows the page:
 * each of the frontend's routes (debridav-frontend's src/router/index.tsx).
 */
class FrontendRoutingConfigurationTest {
    private val mvc: MockMvc = AnnotationConfigWebApplicationContext().run {
        servletContext = MockServletContext()
        register(Mvc::class.java, FrontendRoutingConfiguration::class.java)
        refresh()
        MockMvcBuilders.webAppContextSetup(this).build()
    }

    @Configuration
    @EnableWebMvc
    class Mvc

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/login", "/files", "/usenet", "/torrents", "/health", "/logs", "/config/core", "/config/indexers",
            "/movies", "/tv", "/tv/1399", "/play/movie/603", "/play/series/1399/1/2",
        ],
    )
    fun `a page of the app serves the app`(path: String) {
        mvc.perform(get(path)).andExpect(forwardedUrl("/index.html"))
    }
}
