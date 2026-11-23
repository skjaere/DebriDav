package io.skjaere.debridav.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.context.ApplicationContext
import org.springframework.core.ResolvableType
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaType

data class ConfigPropertyMeta(
    val name: String,
    val description: String,
    val sensitive: Boolean = false,
    val group: String,
    val type: String = "STRING",
    val advanced: Boolean = false
)

@Component
class ConfigPropertyRegistry(
    private val applicationContext: ApplicationContext
) {
    private val logger = LoggerFactory.getLogger(ConfigPropertyRegistry::class.java)
    private val _properties = mutableMapOf<String, ConfigPropertyMeta>()
    private val _testers = mutableMapOf<String, ConfigurationTester>()
    private val _declaredTypes = mutableMapOf<String, ResolvableType>()

    val properties: Map<String, ConfigPropertyMeta> get() = _properties

    @Suppress("LoopWithTooManyJumpStatements")
    @PostConstruct
    fun init() {
        val beanNames = applicationContext.getBeanNamesForAnnotation(ConfigurationProperties::class.java)
        for (beanName in beanNames) {
            val beanType = applicationContext.getType(beanName) ?: continue
            val prefix = beanType.getAnnotation(ConfigurationProperties::class.java)?.prefix
                ?: continue

            for (prop in beanType.kotlin.memberProperties) {
                val annotation = prop.findAnnotation<ConfigProperty>() ?: continue

                val kebabName = camelToKebab(prop.name)
                val key = "$prefix.$kebabName"
                val group = annotation.group.ifEmpty { deriveGroup(prefix) }
                val type = when (prop.returnType.classifier) {
                    Boolean::class -> "BOOLEAN"
                    Int::class -> "INT"
                    Long::class -> "LONG"
                    Duration::class -> "DURATION"
                    List::class -> "STRING_LIST"
                    else -> "STRING"
                }

                _declaredTypes[key] = ResolvableType.forType(prop.returnType.javaType)
                _properties[key] = ConfigPropertyMeta(
                    name = annotation.name,
                    description = annotation.description,
                    sensitive = annotation.sensitive,
                    group = group,
                    type = type,
                    advanced = annotation.advanced
                )
            }
        }
        discoverTesters()
    }

    private fun discoverTesters() {
        val testers = applicationContext.getBeansOfType(ConfigurationTester::class.java).values
        for (tester in testers) {
            val prefix = tester.configurationClass.java
                .getAnnotation(ConfigurationProperties::class.java)
                ?.prefix
            if (prefix != null) {
                _testers[prefix] = tester
                logger.info("Registered configuration tester '{}' for prefix '{}'", tester.label, prefix)
            } else {
                logger.warn(
                    "ConfigurationTester '{}' targets {} which has no @ConfigurationProperties annotation",
                    tester.label, tester.configurationClass
                )
            }
        }
    }

    fun getTester(prefix: String): ConfigurationTester? = _testers[prefix]

    /**
     * Why [value] can't be bound to [key]'s declared type, or null when it can: binds it the way
     * the context refresh will, so a value that would fail there (an Int of "1.5", a Duration of
     * "soon", an unknown enum in a list) is rejected before it is saved. A blank value is accepted
     * only for strings and lists, where it means empty. Keys outside the registry aren't checked.
     */
    fun bindingError(key: String, value: String): String? {
        val type = _declaredTypes[key]
        return when {
            type == null -> null
            value.isBlank() && !type.acceptsBlank() -> "a value is required"
            else -> bindFailure(key, value, type)
        }
    }

    private fun bindFailure(key: String, value: String, type: ResolvableType): String? = try {
        Binder(MapConfigurationPropertySource(mapOf(key to value))).bind(key, Bindable.of<Any>(type))
        null
    } catch (e: BindException) {
        generateSequence<Throwable>(e) { it.cause }.last().message ?: e.message
    }

    private fun ResolvableType.acceptsBlank(): Boolean {
        val raw = resolve() ?: return true
        return raw == String::class.java || Collection::class.java.isAssignableFrom(raw) || raw.isArray
    }

    fun getTestablePrefixes(): List<TestablePrefixDto> = _testers.map { (prefix, tester) ->
        TestablePrefixDto(prefix = prefix, label = tester.label)
    }

    fun isWhitelisted(key: String): Boolean = _properties.containsKey(key)

    fun getMeta(key: String): ConfigPropertyMeta? = _properties[key]

    companion object {
        private val PROVIDER_PREFIXES = setOf("premiumize", "real-debrid", "torbox", "easynews")
        private val ARR_PREFIXES = setOf("sonarr", "radarr")

        fun camelToKebab(name: String): String = buildString {
            for ((i, ch) in name.withIndex()) {
                if (ch.isUpperCase()) {
                    if (i > 0) append('-')
                    append(ch.lowercaseChar())
                } else {
                    append(ch)
                }
            }
        }

        fun deriveGroup(prefix: String): String = when (prefix) {
            in PROVIDER_PREFIXES -> "providers"
            in ARR_PREFIXES -> "arrs"
            else -> prefix
        }
    }
}
