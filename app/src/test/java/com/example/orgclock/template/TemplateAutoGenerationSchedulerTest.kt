package com.example.orgclock.template

import android.content.SharedPreferences
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.time.toKotlinLocalDateCompat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.time.ZoneId
import java.time.ZonedDateTime

class TemplateAutoGenerationSchedulerTest {
    @Test
    fun sync_generatesUsingExplicitTemplateAndSchedulesNextRun() = runTest {
        val prefs = FakeSharedPreferences()
        val store = RootScheduleStore(prefs)
        val reporter = FakeFailureReporter()
        val workScheduler = FakeWorkScheduler()
        val repository = FakeTemplateRepository(
            generationResult = TemplateGenerationResult.Generated,
        )
        val rootUri = mock(android.net.Uri::class.java).apply {
            doReturn("content://orgclock/root").`when`(this).toString()
        }
        val now = ZonedDateTime.of(2026, 3, 12, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo"))
        val scheduler = TemplateAutoGenerationScheduler(
            appContext = mock(android.content.Context::class.java),
            scheduleStore = store,
            failureReporter = reporter,
            repositoryFactory = { repository },
            workScheduler = workScheduler,
            logError = {},
            nowProvider = { now },
        )
        val config = RootScheduleConfig(
            rootUri = rootUri.toString(),
            enabled = true,
            hour = 9,
            minute = 0,
            templateFileUri = "content://orgclock/root/template-source",
        )

        scheduler.sync(rootUri, config)

        assertEquals(RootReference(config.rootUri), repository.openedRoot)
        assertEquals(now.toLocalDate().toKotlinLocalDateCompat(), repository.generatedDate)
        assertEquals(config.templateFileUri, repository.generatedTemplateFileUri)
        assertEquals(config.rootUri, workScheduler.scheduledRootUri)
        assertTrue(reporter.clearedRoots.contains(config.rootUri))
        assertEquals(config.templateFileUri, store.load(config.rootUri).templateFileUri)
    }

    @Test
    fun runWorker_reportsTemplateFailuresFromStoredConfig() = runTest {
        val prefs = FakeSharedPreferences()
        val store = RootScheduleStore(prefs)
        val reporter = FakeFailureReporter()
        val workScheduler = FakeWorkScheduler()
        val repository = FakeTemplateRepository(
            generationResult = TemplateGenerationResult.Failed(
                reason = "Template file is missing",
                kind = TemplateGenerationFailureKind.TemplateMissing,
            ),
        )
        val rootUri = "content://orgclock/root"
        store.save(
            RootScheduleConfig(
                rootUri = rootUri,
                enabled = true,
                hour = 9,
                minute = 0,
                templateFileUri = "content://orgclock/root/template-source",
            ),
        )
        val rootUriRef = mock(android.net.Uri::class.java).apply {
            doReturn(rootUri).`when`(this).toString()
        }
        val scheduler = TemplateAutoGenerationScheduler(
            appContext = mock(android.content.Context::class.java),
            scheduleStore = store,
            failureReporter = reporter,
            repositoryFactory = { repository },
            workScheduler = workScheduler,
            logError = {},
            nowProvider = { ZonedDateTime.of(2026, 3, 12, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo")) },
        )

        scheduler.runWorker(rootUriRef)

        assertEquals("Template auto-generation failed", reporter.lastTitle)
        assertEquals("Template file is missing", reporter.lastMessage)
        assertEquals(rootUri, reporter.lastRootUri)
        assertEquals("content://orgclock/root/template-source", repository.generatedTemplateFileUri)
        assertEquals(rootUri, workScheduler.scheduledRootUri)
    }
}

private class FakeTemplateRepository(
    private val openRootResult: Result<Unit> = Result.success(Unit),
    private val generationResult: TemplateGenerationResult,
) : TemplateAutoGenerationRepository {
    var openedRoot: RootReference? = null
    var generatedDate: kotlinx.datetime.LocalDate? = null
    var generatedTemplateFileUri: String? = null

    override suspend fun openRoot(rootReference: RootReference): Result<Unit> {
        openedRoot = rootReference
        return openRootResult
    }

    override suspend fun createDailyFromTemplateIfMissing(
        date: kotlinx.datetime.LocalDate,
        templateFileUri: String?,
    ): Result<TemplateGenerationResult> {
        generatedDate = date
        generatedTemplateFileUri = templateFileUri
        return Result.success(generationResult)
    }
}

private class FakeWorkScheduler : TemplateAutoGenerationWorkScheduler {
    var scheduledRootUri: String? = null
    var scheduledDelayMs: Long? = null
    var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override fun schedule(rootUri: android.net.Uri, delayMs: Long) {
        scheduledRootUri = rootUri.toString()
        scheduledDelayMs = delayMs
    }
}

private class FakeFailureReporter : TemplateAutoGenerationFailureReporter {
    var lastRootUri: String? = null
    var lastTitle: String? = null
    var lastMessage: String? = null
    val clearedRoots = mutableListOf<String>()

    override fun clear(rootUri: String) {
        clearedRoots += rootUri
    }

    override fun reportFailure(rootUri: String, title: String, message: String) {
        lastRootUri = rootUri
        lastTitle = title
        lastMessage = message
    }

    override fun loadLastFailure(rootUri: String): String? = null
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor(values)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { values[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { this.values[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { this.values[key!!] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { this.values[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { this.values[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { values.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { values.clear() }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}
