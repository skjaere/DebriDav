package io.skjaere.debridav.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.ResolvableType
import org.springframework.core.type.filter.AnnotationTypeFilter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaType

/**
 * Checks config override values against the declared types of the `@ConfigProperty` settings
 * they override. Usable before the application context exists, so overrides can be checked when
 * they are loaded at the very start of startup.
 */
class ConfigValueChecker(private val declaredTypes: Map<String, ResolvableType>) {

    /**
     * Why [value] can't be bound to [key]'s declared type, or null when it can: binds it the way
     * the context refresh will, so a value that would fail there (an Int of "1.5", a Duration of
     * "soon", an unknown enum in a list) is caught first. A blank value is accepted only for
     * strings and lists, where it means empty. Keys without a declared type aren't checked.
     */
    fun bindingError(key: String, value: String): String? {
        val type = declaredTypes[key]
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

    companion object {
        /** The declared type of each `@ConfigProperty` in [classes], by its property key. */
        fun declaredTypes(classes: Iterable<Class<*>>): Map<String, ResolvableType> =
            classes.flatMap { type ->
                val prefix = type.getAnnotation(ConfigurationProperties::class.java)?.prefix
                    ?: return@flatMap emptyList()
                type.kotlin.memberProperties
                    .filter { it.findAnnotation<ConfigProperty>() != null }
                    .map { "$prefix.${ConfigPropertyRegistry.camelToKebab(it.name)}" to
                        ResolvableType.forType(it.returnType.javaType) }
            }.toMap()

        /** Finds the `@ConfigurationProperties` classes under [basePackage] on the classpath. */
        fun scan(basePackage: String, classLoader: ClassLoader?): ConfigValueChecker {
            val scanner = ClassPathScanningCandidateComponentProvider(false)
            scanner.addIncludeFilter(AnnotationTypeFilter(ConfigurationProperties::class.java))
            val classes = scanner.findCandidateComponents(basePackage).mapNotNull { definition ->
                definition.beanClassName?.let { Class.forName(it, false, classLoader) }
            }
            return ConfigValueChecker(declaredTypes(classes))
        }
    }
}
