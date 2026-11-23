package io.skjaere.debridav.configuration

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component

/**
 * Single source of truth for the User-Agent header sent on outbound calls to debrid /
 * streaming providers. Format follows the common bot convention:
 *   `DebriDav/<version> (+https://github.com/skjaere/DebriDav)`
 *
 * The version is read from Spring Boot's BuildProperties bean, populated at build time
 * by the `springBoot.buildInfo()` task and packaged into `META-INF/build-info.properties`.
 * Falls back to "dev" if the build-info file is absent (e.g. when running from an IDE
 * without the bootBuildInfo task wired in).
 */
@Component
class DebridavUserAgent(buildProperties: ObjectProvider<BuildProperties>) {
    val value: String = "DebriDav/${buildProperties.ifAvailable?.version ?: "dev"} " +
            "(+https://github.com/skjaere/DebriDav)"
}
