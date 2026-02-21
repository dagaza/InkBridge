package com.inkbridge

import java.io.OutputStream

/**
 * A zero-allocation circular buffer designed specifically for 22-byte PenPackets.
 * It safely passes data from the UI thread to the background writer thread
 * without triggering the Garbage Collector.
 */
class PacketRingBuffer(capacityPackets: Int) {
    private val packetSize = 22
    private val buffer = ByteArray(capacityPackets * packetSize)
    private var head = 0
    private var tail = 0
    private var count = 0
    private val lock = Object()

    fun write(data: ByteArray, offset: Int, length: Int) {
        if (length != packetSize) return
        synchronized(lock) {
            if (count >= buffer.size) return // Queue full, drop packet (backpressure)
            System.arraycopy(data, offset, buffer, tail, packetSize)
            tail = (tail + packetSize) % buffer.size
            count += packetSize
            lock.notify()
        }
    }

    fun waitForDataAndDrain(stream: OutputStream, timeoutMs: Long): Boolean {
        synchronized(lock) {
            if (count == 0) {
                lock.wait(timeoutMs)
            }
            if (count == 0) return false // Still empty after timeout

            // Drain the entire ring buffer directly to the stream
            while (count > 0) {
                val chunk = minOf(count, buffer.size - head)
                stream.write(buffer, head, chunk)
                head = (head + chunk) % buffer.size
                count -= chunk
            }
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            tail = 0
            count = 0
        }
    }
}