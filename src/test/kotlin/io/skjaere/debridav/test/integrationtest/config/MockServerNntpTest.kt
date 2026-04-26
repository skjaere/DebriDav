package io.skjaere.debridav.test.integrationtest.config

import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.ContextConfiguration

/**
 * Same wiring as [MockServerTest] but adds [NntpPoolInitializer] so the NNTP pool
 * properties are populated from the mock NNTP container. Use on tests that exercise
 * the NZB import or streaming path.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ContextConfiguration(
    initializers = [TestContextInitializer::class, NntpPoolInitializer::class],
    classes = [PremiumizeStubbingService::class]
)
@AutoConfigureWebTestClient
annotation class MockServerNntpTest
