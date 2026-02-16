package com.example.orgclock.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.startActivityAndWait
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupAndScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() {
        benchmarkRule.measureRepeated(
            packageName = "com.example.orgclock",
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Partial(),
        ) {
            pressHome()
            startActivityAndWait()
            UiDevice.getInstance(instrumentation).wait(Until.hasObject(By.textContains("Org Clock")), 5_000)
        }
    }

    @Test
    fun filePickerFrameTiming() {
        benchmarkRule.measureRepeated(
            packageName = "com.example.orgclock",
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Partial(),
        ) {
            pressHome()
            startActivityAndWait()
            UiDevice.getInstance(instrumentation).wait(Until.hasObject(By.textContains("Select org directory")), 5_000)
        }
    }

    @Test
    fun headingListScrollFrameTiming() {
        benchmarkRule.measureRepeated(
            packageName = "com.example.orgclock",
            metrics = listOf(
                FrameTimingMetric(),
            ),
            iterations = 5,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Partial(),
        ) {
            pressHome()
            startActivityAndWait()

            // Root setup/file picker only. Scroll is attempted if heading list is visible.
            val device = UiDevice.getInstance(instrumentation)
            device.wait(Until.hasObject(By.textContains("Org Clock")), 5_000)
            val scrollable = device.findObject(By.scrollable(true))
            if (scrollable != null) {
                scrollable.fling(Direction.DOWN)
                scrollable.fling(Direction.UP)
            }
        }
    }
}
