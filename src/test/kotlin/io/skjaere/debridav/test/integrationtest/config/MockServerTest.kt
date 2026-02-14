package io.skjaere.debridav.test.integrationtest.config

import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.ContextConfiguration

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ContextConfiguration(initializers = [TestContextInitializer::class], classes = [PremiumizeStubbingService::class])
@AutoConfigureWebTestClient
annotation class MockServerTest
