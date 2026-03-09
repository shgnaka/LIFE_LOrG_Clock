package com.example.orgclock.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationCoordinatorTest {
    @Test
    fun runExclusive_sameFile_serializesCallers() = runBlocking {
        val coordinator = InMemoryFileOperationCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            coordinator.runExclusive("f1") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        val second = async {
            firstEntered.await()
            coordinator.runExclusive("f1") {
                secondEntered.complete(Unit)
            }
        }

        firstEntered.await()
        delay(50)
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun runExclusive_differentFiles_allowsParallelCallers() = runBlocking {
        val coordinator = InMemoryFileOperationCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseBoth = CompletableDeferred<Unit>()

        val first = async {
            coordinator.runExclusive("f1") {
                firstEntered.complete(Unit)
                releaseBoth.await()
            }
        }
        val second = async {
            coordinator.runExclusive("f2") {
                secondEntered.complete(Unit)
                releaseBoth.await()
            }
        }

        firstEntered.await()
        secondEntered.await()
        assertTrue(firstEntered.isCompleted)
        assertTrue(secondEntered.isCompleted)

        releaseBoth.complete(Unit)
        first.await()
        second.await()
    }
}
