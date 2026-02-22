package com.example.orgclock.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

interface NotificationPermissionChecker {
    fun isGranted(context: Context): Boolean
}

class DefaultNotificationPermissionChecker(
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val notificationsEnabledProvider: (Context) -> Boolean = {
        NotificationManagerCompat.from(it).areNotificationsEnabled()
    },
    private val runtimePermissionGrantedProvider: (Context) -> Boolean = {
        ContextCompat.checkSelfPermission(
            it,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    },
) : NotificationPermissionChecker {
    override fun isGranted(context: Context): Boolean {
        val notificationsEnabled = notificationsEnabledProvider(context)
        if (sdkIntProvider() < Build.VERSION_CODES.TIRAMISU) {
            return notificationsEnabled
        }
        val runtimeGranted = runtimePermissionGrantedProvider(context)
        return runtimeGranted && notificationsEnabled
    }
}
