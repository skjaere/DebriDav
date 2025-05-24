package io.skjaere.debridav.cache

data class BytesToCache(
    var bytes: ByteArray,
    val startByte: Long,
    var endByte: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BytesToCache

        if (startByte != other.startByte) return false
        if (endByte != other.endByte) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = startByte.hashCode()
        result = 31 * result + endByte.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
