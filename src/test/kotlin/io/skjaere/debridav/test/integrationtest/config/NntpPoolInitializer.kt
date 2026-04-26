package io.skjaere.debridav.test.integrationtest.config

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext

/**
 * Wires the mock NNTP server (started by [TestContextInitializer]) into `nntp.pools[0]`.
 * Apply via `@ContextConfiguration(initializers = [NntpPoolInitializer::class])` on tests
 * that exercise the NZB import / streaming path. Tests that don't need NNTP (e.g.
 * easynews-only paths) should omit it so [SabNzbdService.isEasynewsOnlySetup] returns true.
 */
class NntpPoolInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        val container = TestContextInitializer.mockNntpServerContainer
        TestPropertyValues.of(
            "nntp.pools[0].host=${container.nntpHost}",
            "nntp.pools[0].port=${container.nntpPort}",
            "nntp.pools[0].use-tls=false",
        ).applyTo(applicationContext)
    }
}
