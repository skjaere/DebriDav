package io.skjaere.debridav.config

import kotlin.reflect.KClass

interface ConfigurationTester {
    val configurationClass: KClass<*>
    val label: String
    suspend fun test(overrides: Map<String, String> = emptyMap()): TestResult
}

data class TestResult(
    val success: Boolean,
    val message: String
)
