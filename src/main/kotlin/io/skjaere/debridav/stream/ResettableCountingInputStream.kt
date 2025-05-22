package io.skjaere.debridav.stream

import com.google.common.io.CountingInputStream
import java.io.InputStream

class ResettableCountingInputStream(private val countingInputStream: CountingInputStream) : InputStream() {
    private var bytesTransferred: Long = 0

    constructor(inputStream: InputStream) : this(CountingInputStream(inputStream))

    fun countAndReset(): Long {
        val count = countingInputStream.count
        val transferredSinceLastCheck = count - bytesTransferred
        bytesTransferred = count
        return transferredSinceLastCheck
    }

    override fun read(): Int {
        return countingInputStream.read()
    }

    override fun close() {
        super.close()
    }
}
