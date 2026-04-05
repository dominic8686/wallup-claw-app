package com.wallupclaw.app.audio

import java.nio.ShortBuffer

class FixedRingBuffer<T>(private val capacity: Int) {
    private val buffer = arrayOfNulls<Any?>(capacity)
    private var start = 0
    private var count = 0
    fun add(value: T) {
        buffer[(start + count) % capacity] = value
        if (count == capacity) { start = (start + 1) % capacity } else { count++ }
    }
    fun takeLast(n: Int): List<T> {
        val actual = minOf(n, count)
        return List(actual) { i -> @Suppress("UNCHECKED_CAST") (buffer[(start + count - actual + i) % capacity] as T) }
    }
    fun clear() { start = 0; count = 0; for (i in buffer.indices) buffer[i] = null }
}

class ShortRingBuffer(private val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var writePos = 0
    private var size = 0
    fun put(src: ShortBuffer, length: Int) {
        require(length <= capacity)
        var remaining = length
        while (remaining > 0) {
            val writeLen = minOf(remaining, capacity - writePos)
            src.get(buffer, writePos, writeLen)
            writePos = (writePos + writeLen) % capacity
            remaining -= writeLen
        }
        size = minOf(size + length, capacity)
    }
    fun available(): Int = size
    fun peekFrom(startOffset: Int, out: ShortArray, outOffset: Int, length: Int): Boolean {
        if (startOffset + length > size) return false
        val startPos = (writePos - size + startOffset + capacity) % capacity
        var remaining = length; var readPos = startPos; var outPos = outOffset
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - readPos)
            System.arraycopy(buffer, readPos, out, outPos, chunk)
            readPos = (readPos + chunk) % capacity; outPos += chunk; remaining -= chunk
        }
        return true
    }
    fun clear() { writePos = 0; size = 0; buffer.fill(0) }
}
