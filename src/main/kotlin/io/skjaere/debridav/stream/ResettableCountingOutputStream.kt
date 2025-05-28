package io.skjaere.debridav.stream

import com.google.common.io.CountingOutputStream
import java.io.OutputStream

class ResettableCountingOutputStream(private val countingOutputStream: CountingOutputStream) : OutputStream() {
    private var bytesTransferred: Long = 0

    constructor(outputStream: OutputStream) : this(CountingOutputStream(outputStream))

    override fun write(b: Int) {
        countingOutputStream.write(b)
    }

    fun countAndReset(): Long {
        val count = countingOutputStream.count
        val transferredSinceLastCheck = count - bytesTransferred
        bytesTransferred = count
        return transferredSinceLastCheck
    }
}
