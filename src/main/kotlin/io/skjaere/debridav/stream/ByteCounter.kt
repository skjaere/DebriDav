package io.skjaere.debridav.stream

import java.util.concurrent.atomic.AtomicLong

class ByteCounter {
    private val total = AtomicLong(0)
    private val lastChecked = AtomicLong(0)

    fun add(bytes: Long) {
        total.addAndGet(bytes)
    }

    fun countAndReset(): Long {
        val current = total.get()
        val previous = lastChecked.getAndSet(current)
        return current - previous
    }
}
