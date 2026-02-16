package com.example.orgclock

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.ui.app.OrgClockRoute
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.theme.OrgClockTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("org-clock", MODE_PRIVATE)
        val repository = SafOrgRepository(this)
        val clockService = ClockService(repository)
        val showPerfOverlay = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        setContent {
            val performanceMonitor = remember { PerformanceMonitor(window) }
            OrgClockTheme {
                OrgClockRoute(
                    loadSavedUri = { prefs.getString("root_uri", null)?.let(Uri::parse) },
                    saveUri = { uri -> prefs.edit().putString("root_uri", uri.toString()).apply() },
                    openRoot = { uri -> repository.openRoot(uri) },
                    listFiles = { repository.listOrgFiles() },
                    listHeadings = { fileId -> clockService.listHeadings(fileId) },
                    startClock = { fileId, lineIndex ->
                        clockService.startClockInFile(fileId, lineIndex, java.time.ZonedDateTime.now())
                    },
                    stopClock = { fileId, lineIndex ->
                        clockService.stopClockInFile(fileId, lineIndex, java.time.ZonedDateTime.now())
                    },
                    cancelClock = { fileId, lineIndex ->
                        clockService.cancelClockInFile(fileId, lineIndex)
                    },
                    listClosedClocks = { fileId, lineIndex ->
                        clockService.listClosedClocksInFile(fileId, lineIndex, java.time.ZonedDateTime.now())
                    },
                    editClosedClock = { fileId, headingLineIndex, clockLineIndex, start, end ->
                        clockService.editClosedClockInFile(fileId, headingLineIndex, clockLineIndex, start, end)
                    },
                    performanceMonitor = performanceMonitor,
                    showPerfOverlay = showPerfOverlay,
                )
            }
        }
    }
}
