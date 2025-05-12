package io.skjaere.debridav.stream

import io.milton.http.Range
import io.skjaere.debridav.debrid.DebridProvider

data class ByteArrayContext(
    val byteArray: ByteArray,
    val range: Range,
    val source: ByteArraySource,
    val debridProvider: DebridProvider? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ByteArrayContext

        if (!byteArray.contentEquals(other.byteArray)) return false
        if (range != other.range) return false
        if (source != other.source) return false
        if (debridProvider != other.debridProvider) return false

        return true
    }

    override fun hashCode(): Int {
        var result = byteArray.contentHashCode()
        result = 31 * result + range.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (debridProvider?.hashCode() ?: 0)
        return result
    }
}
