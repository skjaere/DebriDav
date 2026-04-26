package io.skjaere.debridav.rclone

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Rclone remote-control endpoint used by [RcloneCacheInvalidator].
 *
 * Env-only on purpose — these are infrastructure details that get set once
 * per deployment, not user preferences. The user-facing toggle lives on
 * `DebridavConfigurationProperties.rcloneCacheInvalidationEnabled`.
 */
@ConfigurationProperties(prefix = "debridav.rclone")
class RcloneConfigurationProperties {
    /** e.g. `http://rclone:5572`. Blank disables the integration. */
    var rcUrl: String = ""
    var rcUser: String = ""
    var rcPassword: String = ""
}
