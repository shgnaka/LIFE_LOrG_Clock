package com.example.orgclock.sync

/**
 * Source of incoming sync command payloads.
 * Implementations push raw payload JSON into the supplied callback.
 */
interface IncomingCommandSource {
    suspend fun start(onCommand: suspend (String) -> Unit)
    suspend fun stop()
}

class NoOpIncomingCommandSource : IncomingCommandSource {
    override suspend fun start(onCommand: suspend (String) -> Unit) {
    }

    override suspend fun stop() {
    }
}

internal class IncomingCommandBuffer(
    private val maxSize: Int,
) {
    private val lock = Any()
    private val queue = ArrayDeque<String>()

    fun add(payload: String): Boolean {
        synchronized(lock) {
            val overflowed = queue.size >= maxSize
            if (overflowed) queue.removeFirst()
            queue.addLast(payload)
            return overflowed
        }
    }

    fun drainAll(): List<String> {
        synchronized(lock) {
            if (queue.isEmpty()) return emptyList()
            val drained = queue.toList()
            queue.clear()
            return drained
        }
    }
}
