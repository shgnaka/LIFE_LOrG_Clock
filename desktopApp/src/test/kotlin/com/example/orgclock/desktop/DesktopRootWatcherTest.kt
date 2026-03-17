package com.example.orgclock.desktop

import com.example.orgclock.ui.state.ExternalChangeNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRootWatcherTest {
    private val tempRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tempRoots.clear()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun invalidWatchKey_reRegistersRootAndEmitsGenericReloadNotice() = runTest {
        val root = tempRoot()
        val notices = mutableListOf<ExternalChangeNotice>()
        val watchService = FakeWatchService()
        var registerCount = 0
        val invalidKey = FakeWatchKey(
            events = emptyList(),
            resetResult = false,
        )
        watchService.enqueue(invalidKey)

        val watcher = DesktopRootWatcher(
            rootPath = root,
            scope = backgroundScope,
            onChange = notices::add,
            watchServiceFactory = { watchService },
            registerRootDirectory = { _, _ ->
                registerCount += 1
                invalidKey
            },
        )

        watcher.start()
        waitFor { notices.isNotEmpty() }
        watcher.stop()

        assertEquals(2, registerCount)
        assertEquals(emptySet(), notices.single().changedFileIds)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun orgFileChange_reportsNormalizedOrgPathOnly() = runTest {
        val root = tempRoot()
        val notices = mutableListOf<ExternalChangeNotice>()
        val watchService = FakeWatchService()
        val key = FakeWatchKey(
            events = listOf(
                FakeWatchEvent(kind = ENTRY_MODIFY, context = Path.of("2026-03-16.org")),
                FakeWatchEvent(kind = ENTRY_MODIFY, context = Path.of("notes.txt")),
            ),
            resetResult = true,
        )
        watchService.enqueue(key)

        val watcher = DesktopRootWatcher(
            rootPath = root,
            scope = backgroundScope,
            onChange = notices::add,
            watchServiceFactory = { watchService },
            registerRootDirectory = { _, _ -> key },
        )

        watcher.start()
        waitFor { notices.isNotEmpty() }
        watcher.stop()

        assertEquals(1, notices.size)
        assertTrue(notices.single().changedFileIds.single().endsWith("2026-03-16.org"))
    }

    private suspend fun waitFor(predicate: () -> Boolean) {
        repeat(50) {
            if (predicate()) return
            delay(20)
        }
        error("Timed out waiting for watcher event")
    }

    private fun tempRoot(): Path = createTempDirectory("desktop-root-watcher-test").also(tempRoots::add)

    private class FakeWatchService : WatchService {
        private val queue = LinkedBlockingQueue<WatchKey>()
        @Volatile
        private var closed = false

        fun enqueue(key: WatchKey) {
            queue.put(key)
        }

        override fun poll(): WatchKey? = queue.poll()

        override fun poll(timeout: Long, unit: TimeUnit): WatchKey? = queue.poll(timeout, unit)

        override fun take(): WatchKey {
            while (true) {
                if (closed) throw ClosedWatchServiceException()
                queue.poll(20, TimeUnit.MILLISECONDS)?.let { return it }
            }
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeWatchKey(
        private val events: List<WatchEvent<Path>>,
        private val resetResult: Boolean,
    ) : WatchKey {
        override fun isValid(): Boolean = resetResult

        override fun pollEvents(): MutableList<WatchEvent<*>> = events.toMutableList()

        override fun reset(): Boolean = resetResult

        override fun cancel() = Unit

        override fun watchable(): Path = Path.of(".")
    }

    private class FakeWatchEvent<T>(
        private val kind: WatchEvent.Kind<T>,
        private val context: T,
        private val count: Int = 1,
    ) : WatchEvent<T> {
        override fun kind(): WatchEvent.Kind<T> = kind

        override fun count(): Int = count

        override fun context(): T = context
    }
}
