package io.skjaere.debridav.config

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigProperty(
    val name: String,
    val description: String = "",
    val sensitive: Boolean = false,
    val group: String = "",
    val advanced: Boolean = false,
    /** Read only at startup (a schedule, a cache built once): a change applies after a restart. */
    val requiresRestart: Boolean = false,
)
