package com.example.orgclock.sync

/**
 * Source of incoming sync command payloads.
 * Implementations push raw payload JSON into the supplied callback.
 */
interface IncomingCommandSource {
    suspend fun start(onCommand: suspend (VerifiedIncomingCommand) -> Unit)
    suspend fun stop()
}

class NoOpIncomingCommandSource : IncomingCommandSource {
    override suspend fun start(onCommand: suspend (VerifiedIncomingCommand) -> Unit) {
    }

    override suspend fun stop() {
    }
}

internal class IncomingCommandBuffer(
    private val maxSize: Int,
) {
    private val lock = Any()
    private val queue = ArrayDeque<VerifiedIncomingCommand>()

    fun add(command: VerifiedIncomingCommand): Boolean {
        synchronized(lock) {
            val overflowed = queue.size >= maxSize
            if (overflowed) queue.removeFirst()
            queue.addLast(command)
            return overflowed
        }
    }

    fun drainAll(): List<VerifiedIncomingCommand> {
        synchronized(lock) {
            if (queue.isEmpty()) return emptyList()
            val drained = queue.toList()
            queue.clear()
            return drained
        }
    }
}
