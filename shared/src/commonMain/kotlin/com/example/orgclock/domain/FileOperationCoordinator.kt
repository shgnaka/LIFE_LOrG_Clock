package com.example.orgclock.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface FileOperationCoordinator {
    suspend fun <T> runExclusive(fileId: String, block: suspend () -> T): T
}

object NoOpFileOperationCoordinator : FileOperationCoordinator {
    override suspend fun <T> runExclusive(fileId: String, block: suspend () -> T): T = block()
}

class InMemoryFileOperationCoordinator : FileOperationCoordinator {
    private val mapMutex = Mutex()
    private val mutexByFileId = mutableMapOf<String, Mutex>()

    override suspend fun <T> runExclusive(fileId: String, block: suspend () -> T): T {
        val fileMutex = mapMutex.withLock {
            mutexByFileId.getOrPut(fileId) { Mutex() }
        }
        return fileMutex.withLock {
            block()
        }
    }
}
