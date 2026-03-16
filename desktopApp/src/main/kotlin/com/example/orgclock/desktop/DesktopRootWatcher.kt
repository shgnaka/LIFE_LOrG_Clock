package com.example.orgclock.desktop

import com.example.orgclock.ui.state.ExternalChangeNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import kotlin.io.path.extension

class DesktopRootWatcher(
    private val rootPath: Path,
    private val scope: CoroutineScope,
    private val onChange: (ExternalChangeNotice) -> Unit,
    private val watchServiceFactory: () -> WatchService = { FileSystems.getDefault().newWatchService() },
) {
    private var job: Job? = null
    private var watchService: WatchService? = null
    private var revision = 0L

    fun start() {
        stop()
        val watchService = watchServiceFactory()
        rootPath.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW)
        this.watchService = watchService
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val key = try {
                    watchService.take()
                } catch (_: Throwable) {
                    break
                }
                var overflowed = false
                val changedFileIds = key.pollEvents()
                    .mapNotNull { event ->
                        if (event.kind() == OVERFLOW) {
                            overflowed = true
                            return@mapNotNull null
                        }
                        @Suppress("UNCHECKED_CAST")
                        val relative = (event.context() as? Path) ?: return@mapNotNull null
                        val absolute = rootPath.resolve(relative).toAbsolutePath().normalize()
                        absolute.toString().takeIf { absolute.extension.equals("org", ignoreCase = true) }
                    }
                    .toSet()
                key.reset()
                if (!overflowed && changedFileIds.isEmpty()) continue
                onChange(
                    ExternalChangeNotice(
                        revision = ++revision,
                        changedFileIds = changedFileIds,
                    ),
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        watchService?.close()
        watchService = null
    }
}
