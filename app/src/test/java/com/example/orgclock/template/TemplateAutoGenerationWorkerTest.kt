package com.example.orgclock.template

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateAutoGenerationWorkerTest {
    @Test
    fun run_withoutAnyRootUriSucceedsWithoutSchedulerCall() = runTest {
        var calls = 0
        val runner = TemplateAutoGenerationWorkerRunner(
            schedulerProvider = {
                calls += 1
                error("should not be called")
            },
            uriFactory = { error("should not be called") },
        )

        val result = runner.run(
            appContext = org.mockito.Mockito.mock(Context::class.java),
            inputRootUri = null,
            fallbackRootUri = null,
        )

        assertEquals("Success", result.javaClass.simpleName)
        assertEquals(0, calls)
    }

    @Test
    fun run_withFallbackRootUriInvokesScheduler() = runTest {
        val roots = mutableListOf<String>()
        val runner = TemplateAutoGenerationWorkerRunner(
            schedulerProvider = {
                object : TemplateAutoGenerationScheduler(
                    appContext = it,
                    scheduleStore = RootScheduleStore(InMemorySharedPreferences()),
                    repositoryFactory = { FakeTemplateAutoGenerationRepository() },
                    workScheduler = FakeWorkScheduler(),
                ) {
                    override suspend fun runWorker(rootUri: Uri) {
                        roots += rootUri.toString()
                    }
                }
            },
            uriFactory = {
                org.mockito.Mockito.mock(Uri::class.java).also { uri ->
                    org.mockito.Mockito.`when`(uri.toString()).thenReturn(it)
                }
            },
        )

        val result = runner.run(
            appContext = org.mockito.Mockito.mock(Context::class.java),
            inputRootUri = null,
            fallbackRootUri = "content://fallback",
        )

        assertEquals("Success", result.javaClass.simpleName)
        assertEquals(listOf("content://fallback"), roots)
    }

    @Test
    fun run_whenSchedulerThrowsReturnsRetry() = runTest {
        val runner = TemplateAutoGenerationWorkerRunner(
            schedulerProvider = {
                object : TemplateAutoGenerationScheduler(
                    appContext = it,
                    scheduleStore = RootScheduleStore(InMemorySharedPreferences()),
                    repositoryFactory = { FakeTemplateAutoGenerationRepository() },
                    workScheduler = FakeWorkScheduler(),
                ) {
                    override suspend fun runWorker(rootUri: Uri) {
                        error("boom")
                    }
                }
            },
            uriFactory = {
                org.mockito.Mockito.mock(Uri::class.java).also { uri ->
                    org.mockito.Mockito.`when`(uri.toString()).thenReturn(it)
                }
            },
        )

        val result = runner.run(
            appContext = org.mockito.Mockito.mock(Context::class.java),
            inputRootUri = "content://input",
            fallbackRootUri = null,
        )

        assertEquals("Retry", result.javaClass.simpleName)
    }
}
