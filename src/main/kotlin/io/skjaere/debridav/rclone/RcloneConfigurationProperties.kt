package io.skjaere.debridav.rclone

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Rclone remote-control endpoint used by [RcloneCacheInvalidator].
 *
 * Surfaced in the frontend config UI (group `rclone`) so the URL and
 * credentials can be overridden at runtime via [ConfigOverrideService].
 * The user-facing toggle for whether to fire refresh events at all lives
 * on `DebridavConfigurationProperties.rcloneCacheInvalidationEnabled`.
 */
@ConfigurationProperties(prefix = "debridav.rclone")
class RcloneConfigurationProperties {
    @ConfigProperty(
        name = "RC URL",
        description = "Rclone remote-control endpoint, e.g. http://rclone:5572. Blank disables the integration.",
        group = "rclone",
    )
    var rcUrl: String = ""

    @ConfigProperty(
        name = "RC User",
        description = "Username for rclone's RC endpoint (leave blank if no auth).",
        group = "rclone",
    )
    var rcUser: String = ""

    @ConfigProperty(
        name = "RC Password",
        description = "Password for rclone's RC endpoint (leave blank if no auth).",
        sensitive = true,
        group = "rclone",
    )
    var rcPassword: String = ""
}
