package io.skjaere.debridav.test.integrationtest.config

import org.springframework.test.context.ContextConfiguration

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ContextConfiguration(initializers = [TestContextInitializer::class], classes = [PremiumizeStubbingService::class])
annotation class MockServerTest
