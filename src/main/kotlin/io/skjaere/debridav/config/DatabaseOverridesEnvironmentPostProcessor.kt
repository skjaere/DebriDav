package io.skjaere.debridav.config

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.logging.DeferredLogFactory
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

private const val UNDEFINED_TABLE = "42P01"
private const val CONNECT_TIMEOUT_SECONDS = "5"
private const val SOCKET_TIMEOUT_SECONDS = "10"

/**
 * Applies the config overrides saved from the UI before the application context is created, so
 * they also reach what is read only at startup: bean conditions, `@Scheduled` rates, caches built
 * once, the WebDAV credentials Tomcat serves with from its first request. They were applied only
 * at ApplicationReadyEvent, so such settings never took effect, even after a restart.
 *
 * Reads `config_override` over plain JDBC with the datasource settings. On the first start the
 * table doesn't exist yet, and if the database can't be reached the overrides still apply at
 * ApplicationReadyEvent ([DatabasePropertySourceInitializer]), as before. Values that don't bind
 * to their setting's type are skipped with an error, as there: one would stop the app starting.
 * `debridav.config-overrides.load-before-startup=false` turns this off.
 */
class DatabaseOverridesEnvironmentPostProcessor(
    logFactory: DeferredLogFactory,
) : EnvironmentPostProcessor, Ordered {
    private val log = logFactory.getLog(DatabaseOverridesEnvironmentPostProcessor::class.java)

    // After application.yaml and the environment variables are in place.
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val stored = if (environment.getProperty(ENABLED_KEY, Boolean::class.java, true)) {
            storedOverrides(environment)
        } else {
            emptyList()
        }
        if (stored.isEmpty()) return
        val checker = ConfigValueChecker.scan(BASE_PACKAGE, application.classLoader)
        val applicable = stored.filter { override ->
            val error = checker.bindingError(override.key, override.value)
            if (error != null) {
                val shown = if (override.sensitive) "***" else override.value
                log.error(
                    "Ignoring the config override ${override.key}=$shown: $error. Fix or delete it under Configuration"
                )
            }
            error == null
        }.associate { it.key to it.value }
        environment.propertySources.addFirst(DatabasePropertySource(ConcurrentHashMap(applicable)))
        log.info("Applied ${applicable.size} database config override(s) before startup")
    }

    /**
     * The rows of `config_override`, or none when the datasource URL doesn't resolve yet, the
     * table doesn't exist yet (first start), or the database can't be reached.
     */
    private fun storedOverrides(environment: ConfigurableEnvironment): List<StoredOverride> {
        val url = runCatching { environment.getProperty("spring.datasource.url") }.getOrNull()
            ?: return emptyList()
        return try {
            readOverrides(
                url,
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"),
            )
        } catch (e: SQLException) {
            if (e.sqlState != UNDEFINED_TABLE) {
                log.warn("Couldn't read config overrides before startup (${e.message}); they apply once it's ready")
            }
            emptyList()
        }
    }

    private data class StoredOverride(val key: String, val value: String, val sensitive: Boolean)

    private fun readOverrides(url: String, username: String?, password: String?): List<StoredOverride> {
        val properties = Properties().apply {
            username?.let { setProperty("user", it) }
            password?.let { setProperty("password", it) }
            setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS)
            setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS)
        }
        return DriverManager.getConnection(url, properties).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT prop_key, prop_value, sensitive FROM config_override").use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                StoredOverride(
                                    rows.getString("prop_key"),
                                    rows.getString("prop_value") ?: "",
                                    rows.getBoolean("sensitive"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ENABLED_KEY = "debridav.config-overrides.load-before-startup"
        private const val BASE_PACKAGE = "io.skjaere.debridav"
    }
}
