package io.skjaere.debridav.stream

import io.skjaere.debridav.debrid.DebridProvider
import java.util.*

data class InputStreamingContext(
    val counter: ByteCounter,
    val provider: DebridProvider,
    val file: String,
    val client: String,
    val uniqueId: String
) {
    constructor(
        counter: ByteCounter,
        provider: DebridProvider,
        file: String,
        client: String
    ) : this(counter, provider, file, client, UUID.randomUUID().toString())

    override fun equals(other: Any?): Boolean {
        if (other !is InputStreamingContext) return false
        return this.uniqueId == other.uniqueId
    }

    override fun hashCode(): Int {
        return uniqueId.hashCode()
    }
}
