package com.mph070770.sendspinandroid

import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

class AudioJitterBuffer {

    data class Snapshot(
        val queuedChunks: Int,
        val bufferAheadMs: Long,
        val lateDrops: Long,
        // ✅ New: server timestamp (µs) of the earliest queued chunk, or null if empty
        val headServerUs: Long?
    )

    data class Chunk(
        val serverTimestampUs: Long,
        val pcmData: ByteArray
    )

    private val q = PriorityQueue<Chunk>(compareBy { it.serverTimestampUs })
    private val lateDropsCounter = AtomicLong(0L)

    fun clear() {
        synchronized(q) { q.clear() }
    }

    fun isEmpty(): Boolean = synchronized(q) { q.isEmpty() }

    fun offer(serverTsUs: Long, pcm: ByteArray, offsetUs: Long, nowLocalUs: Long) {
        synchronized(q) {
            q.add(Chunk(serverTsUs, pcm))
        }
    }

    fun snapshot(offsetUs: Long): Snapshot {
        val nowLocalUs = System.nanoTime() / 1000L
        val nowServerUs = nowLocalUs + offsetUs

        return synchronized(q) {
            val head = q.peek()?.serverTimestampUs
            val aheadMs = if (head != null) ((head - nowServerUs) / 1000L) else 0L
            Snapshot(
                queuedChunks = q.size,
                bufferAheadMs = aheadMs,
                lateDrops = lateDropsCounter.get(),
                headServerUs = head
            )
        }
    }

    /**
     * Returns the next playable chunk (based on local time mapping),
     * dropping anything that is too late.
     */
    fun pollPlayable(nowLocalUs: Long, offsetUs: Long, lateDropUs: Long): Chunk? {
        val nowServerUs = nowLocalUs + offsetUs

        synchronized(q) {
            while (true) {
                val head = q.peek() ?: return null

                // If too late, drop it.
                val latenessUs = nowServerUs - head.serverTimestampUs
                if (latenessUs > lateDropUs) {
                    q.poll()
                    lateDropsCounter.incrementAndGet()
                    continue
                }

                // If not yet due, let caller wait.
                return q.poll()
            }
        }
    }
}
