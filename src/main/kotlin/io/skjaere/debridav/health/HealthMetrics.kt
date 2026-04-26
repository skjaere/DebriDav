package io.skjaere.debridav.health

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

private const val CHECKS_METRIC = "debridav.health.checks"
private const val REPAIRS_METRIC = "debridav.health.repairs"
private const val CHECK_DURATION_METRIC = "debridav.health.check.duration"
private const val REPAIR_DURATION_METRIC = "debridav.health.repair.duration"

@Component
class HealthMetrics(private val meterRegistry: MeterRegistry) {

    fun recordCheck(type: HealthType, result: CheckResult) {
        meterRegistry.counter(CHECKS_METRIC, "type", type.tag, "result", result.tag).increment()
    }

    fun recordRepair(type: HealthType, action: String) {
        meterRegistry.counter(REPAIRS_METRIC, "type", type.tag, "action", action).increment()
    }

    fun <T> timeCheck(type: HealthType, block: () -> T): T {
        val sample = Timer.start(meterRegistry)
        try {
            return block()
        } finally {
            sample.stop(
                Timer.builder(CHECK_DURATION_METRIC).tag("type", type.tag).register(meterRegistry)
            )
        }
    }

    fun <T> timeRepair(type: HealthType, block: () -> T): T {
        val sample = Timer.start(meterRegistry)
        try {
            return block()
        } finally {
            sample.stop(
                Timer.builder(REPAIR_DURATION_METRIC).tag("type", type.tag).register(meterRegistry)
            )
        }
    }

    enum class HealthType(val tag: String) {
        NZB("nzb"),
        TORRENT("torrent"),
    }

    enum class CheckResult(val tag: String) {
        OK("ok"),
        MISSING("missing"),
        FAILURE("failure"),
        NOT_FOUND("not_found"),
    }
}
