package com.example.orgclock.desktop

import com.example.orgclock.data.DesktopFileOrgRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.sync.JdbcClockEventStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal object DesktopSmokeTestCommand {
    private const val FLAG = "--smoke-test"

    fun isRequested(args: Array<String>): Boolean = FLAG in args

    fun run(args: Array<String>) {
        val options = parseOptions(args)
        val root = Path.of(options.getValue("--root")).absolute().normalize().createDirectories()
        val report = options["--report"]?.let { Path.of(it).absolute().normalize() }
        val checks = mutableListOf<SmokeCheck>()

        val exitCode = runCatching {
            runBlocking {
                prepareFixture(root)
                checks += check("sqlite_event_store") {
                    JdbcClockEventStore.create(root.resolve(".orgclock/clock-events.db")).readSnapshot()
                    require(root.resolve(".orgclock/clock-events.db").exists()) { "SQLite database was not created" }
                }
                checks += check("org_file_read") {
                    val files = DesktopFileOrgRepository(root).listOrgFiles().getOrThrow()
                    require(files.any { it.displayName == "2026-01-02.org" }) { "Smoke org file was not listed" }
                }
                checks += check("clock_start_stop") {
                    val repository = DesktopFileOrgRepository(root)
                    val service = ClockService(repository)
                    val file = repository.listOrgFiles().getOrThrow().first { it.displayName == "2026-01-02.org" }
                    val heading = HeadingPath(listOf("Smoke", "Packaged runtime"))
                    service.startClockInFile(
                        fileId = file.fileId,
                        headingPath = heading,
                        now = Instant.parse("2026-01-02T09:00:00Z"),
                        timeZone = TimeZone.UTC,
                    ).getOrThrow()
                    service.stopClockInFile(
                        fileId = file.fileId,
                        headingPath = heading,
                        now = Instant.parse("2026-01-02T10:00:00Z"),
                        timeZone = TimeZone.UTC,
                    ).getOrThrow()
                    require(root.resolve("2026-01-02.org").readText().contains("CLOCK:")) {
                        "Clock history was not written"
                    }
                }
                checks += check("template_read") {
                    val template = root.resolve(".orgclock-template.org")
                    require(template.exists()) { "Template fixture is missing" }
                    require(template.readText().contains("Packaged runtime")) { "Template contents were not readable" }
                }
                checks += check("desktop_graph_root_open") {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val preferences = Preferences.userRoot().node(
                        "com/example/orgclock/desktop/smoke/${System.nanoTime()}",
                    )
                    val fallbackFile = root.resolve(".orgclock/smoke-root.txt")
                    val graph = DesktopAppGraph(
                        settingsStore = DesktopSettingsStore(preferences, fallbackFile),
                        rootScheduleStore = DesktopRootScheduleStore(preferences.node("schedule")),
                        watchRootChanges = true,
                    )
                    try {
                        val store = graph.createStore(scope)
                        store.onAction(com.example.orgclock.ui.state.OrgClockUiAction.Initialize)
                        store.onAction(com.example.orgclock.ui.state.OrgClockUiAction.PickRoot(
                            com.example.orgclock.presentation.RootReference(root.toString()),
                        ))
                        waitFor("Desktop graph root open", details = {
                            val state = store.uiState.value
                            "screen=${state.screen}, status=${state.status.text.key}, " +
                                "root=${state.rootReference?.rawValue}, files=${state.files.size}"
                        }) {
                            val state = store.uiState.value
                            graph.currentRootReference()?.rawValue
                                ?.let(Path::of)
                                ?.let { runCatching { it.toRealPath() }.getOrNull() } == root.toRealPath() &&
                                state.files.any { it.displayName == "2026-01-02.org" }
                        }
                        require(
                            graph.currentRootReference()?.rawValue
                                ?.let(Path::of)
                                ?.toRealPath() == root.toRealPath(),
                        ) {
                            "Desktop graph did not open the smoke root"
                        }
                    } finally {
                        graph.close()
                        scope.cancel()
                        runCatching {
                            preferences.removeNode()
                            preferences.parent()?.flush()
                        }
                    }
                }
            }
        }.fold(
            onSuccess = { 0 },
            onFailure = { error ->
                checks += SmokeCheck("smoke_runner", false, error.stackTraceToString())
                1
            },
        )

        val json = reportJson(root, checks)
        if (report != null) {
            report.parent?.createDirectories()
            report.writeText(json, StandardCharsets.UTF_8)
        }
        println(json)
        if (exitCode != 0 || checks.any { !it.passed }) {
            throw IllegalStateException("Desktop smoke test failed")
        }
    }

    private fun parseOptions(args: Array<String>): Map<String, String> {
        val values = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg == "--root" || arg == "--report") {
                require(index + 1 < args.size) { "Missing value for $arg" }
                values[arg] = args[index + 1]
                index += 2
            } else {
                index += 1
            }
        }
        require(values["--root"].isNullOrBlank().not()) { "--root is required for --smoke-test" }
        return values
    }

    private fun prepareFixture(root: Path) {
        root.resolve("2026-01-02.org").writeText(
            "* Smoke\n** Packaged runtime\n",
            StandardCharsets.UTF_8,
        )
        root.resolve(".orgclock-template.org").writeText(
            "* Smoke\n** Packaged runtime\n",
            StandardCharsets.UTF_8,
        )
    }

    private suspend fun check(name: String, block: suspend () -> Unit): SmokeCheck =
        runCatching { block() }.fold(
            onSuccess = { SmokeCheck(name, true, null) },
            onFailure = { SmokeCheck(name, false, it.stackTraceToString()) },
        )

    private suspend fun waitFor(
        description: String,
        details: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        repeat(250) {
            if (condition()) return
            kotlinx.coroutines.delay(20)
        }
        error("$description timed out: ${details()}")
    }

    private fun reportJson(root: Path, checks: List<SmokeCheck>): String = buildString {
        append("{\n")
        append("  \"passed\": ${checks.all { it.passed }},\n")
        append("  \"root\": \"${escape(root.toString())}\",\n")
        append("  \"checks\": [\n")
        checks.forEachIndexed { index, check ->
            append("    {\"name\":\"${escape(check.name)}\",\"passed\":${check.passed}")
            check.detail?.let { append(",\"detail\":\"${escape(it)}\"") }
            append("}")
            if (index != checks.lastIndex) append(",")
            append("\n")
        }
        append("  ]\n")
        append("}")
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

    private data class SmokeCheck(
        val name: String,
        val passed: Boolean,
        val detail: String?,
    )
}
