package io.skjaere.debridav.test.collection

import io.skjaere.debridav.collection.play.PlayEvent
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertTrue

/** The names the play's events go to the browser's EventSource by. */
class PlayEventsTest {

    @Test
    fun `no event is named as one EventSource fires itself, so a dropped connection isn't taken for it`() {
        val names = PlayEvent::class.sealedSubclasses.associate { it.simpleName to instance(it).type }
        assertTrue(names.size >= 10, "every event: $names")
        val clashing = names.filterValues { it in EVENT_SOURCE_OWN }
        assertTrue(clashing.isEmpty(), "named as EventSource's own events: $clashing")
    }

    /** One of [type], made with whatever it takes: only its name is read. */
    private fun <T : Any> instance(type: KClass<T>): T = type.objectInstance ?: type.primaryConstructor!!.let { make ->
        make.callBy(make.parameters.filterNot { it.isOptional }.associateWith { value(it.type) })
    }

    private fun value(type: KType): Any? {
        val kind = type.classifier as KClass<*>
        return when {
            type.isMarkedNullable -> null
            kind == String::class -> ""
            kind == Int::class -> 0
            kind == Long::class -> 0L
            kind == Double::class -> 0.0
            kind == Boolean::class -> false
            kind == List::class -> emptyList<Any>()
            kind == Map::class -> emptyMap<Any, Any>()
            else -> instance(kind)
        }
    }

    private companion object {
        /** What EventSource fires on its own: as the connection opens, fails or drops; and for unnamed events. */
        val EVENT_SOURCE_OWN = setOf("open", "error", "message")
    }
}
