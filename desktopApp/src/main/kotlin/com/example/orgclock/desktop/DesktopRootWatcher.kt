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
import java.nio.file.WatchKey
import kotlin.io.path.extension
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class DesktopRootWatcher(
    private val rootPath: Path,
    private val scope: CoroutineScope,
    private val onChange: (ExternalChangeNotice) -> Unit,
    private val watchServiceFactory: () -> WatchService = { FileSystems.getDefault().newWatchService() },
    private val registerRootDirectory: (Path, WatchService) -> WatchKey = { path, watchService ->
        path.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW)
    },
) {
    private var job: Job? = null
    private var watchService: WatchService? = null
    private var watchKey: WatchKey? = null
    private var revision = 0L

    fun start() {
        stop()
        val watchService = watchServiceFactory()
        watchKey = registerRoot(watchService)
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
                val reset = runCatching { key.reset() }.getOrDefault(false)
                if (!reset) {
                    overflowed = true
                    watchKey = runCatching { registerRoot(watchService) }.getOrNull()
                }
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
        watchKey?.cancel()
        watchKey = null
        watchService?.close()
        watchService = null
    }

    private fun registerRoot(watchService: WatchService): WatchKey {
        require(rootPath.exists() && rootPath.isDirectory()) { "Selected root must remain an existing directory" }
        return registerRootDirectory(rootPath, watchService)
    }
}
