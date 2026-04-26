package io.skjaere.debridav.pgmq

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@Suppress("MagicNumber")
@ConfigurationProperties(prefix = "pgmq")
class PgmqConfigurationProperties {
    var defaultVisibilityTimeout: Duration = Duration.ofMinutes(5)
    var importConcurrency: Int = 2
    var importVisibilityTimeout: Duration = Duration.ofMinutes(10)
    var importPollInterval: Duration = Duration.ofSeconds(2)
    var healthCheckConcurrency: Int = 1
    var healthCheckVisibilityTimeout: Duration = Duration.ofMinutes(5)
    var healthCheckPollInterval: Duration = Duration.ofSeconds(10)
    var healthRepairConcurrency: Int = 2
    var healthRepairVisibilityTimeout: Duration = Duration.ofMinutes(2)
    var healthRepairPollInterval: Duration = Duration.ofSeconds(5)
    var archiveRetention: Duration = Duration.ofDays(30)
    var deadLetterRetention: Duration = Duration.ofDays(30)
    var maxReadCount: Long = 5
    var torrentHealthCheckConcurrency: Int = 1
    var torrentHealthCheckVisibilityTimeout: Duration = Duration.ofMinutes(5)
    var torrentHealthCheckPollInterval: Duration = Duration.ofSeconds(10)
    var torrentHealthRepairConcurrency: Int = 2
    var torrentHealthRepairVisibilityTimeout: Duration = Duration.ofMinutes(2)
    var torrentHealthRepairPollInterval: Duration = Duration.ofSeconds(5)
}
