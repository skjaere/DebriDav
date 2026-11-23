package io.skjaere.debridav

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.Configuration

/**
 * Wires Micrometer's static [Metrics.globalRegistry] composite to the
 * Spring-managed [MeterRegistry] bean so meters registered by libraries
 * that reach for `Metrics.globalRegistry` directly (e.g. `nzb-streamer`)
 * end up on the same registry as Spring's own. That way
 * `meterRegistry.find(...)` can locate every meter in the app, regardless
 * of where it was registered, and `/actuator/prometheus` shows a single
 * consistent view.
 */
@Configuration
class MicrometerBridgeConfiguration(
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun addToGlobalRegistry() {
        Metrics.addRegistry(meterRegistry)
    }

    @PreDestroy
    fun removeFromGlobalRegistry() {
        Metrics.removeRegistry(meterRegistry)
    }
}
