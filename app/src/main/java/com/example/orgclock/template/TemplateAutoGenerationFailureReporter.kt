package com.example.orgclock.template

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.orgclock.R
import com.example.orgclock.notification.NotificationPermissionChecker
import com.example.orgclock.notification.NotificationPrefs
import java.security.MessageDigest

interface TemplateAutoGenerationFailureReporter {
    fun clear(rootUri: String)

    fun reportFailure(
        rootUri: String,
        title: String,
        message: String,
    )

    fun loadLastFailure(rootUri: String): String?
}

class SharedPrefsTemplateAutoGenerationFailureReporter(
    private val appContext: Context,
    private val permissionChecker: NotificationPermissionChecker,
) : TemplateAutoGenerationFailureReporter {
    private val prefs by lazy {
        appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun clear(rootUri: String) {
        prefs.edit().remove(key(rootUri)).apply()
        NotificationManagerCompat.from(appContext).cancel(notificationId(rootUri))
    }

    override fun reportFailure(rootUri: String, title: String, message: String) {
        val combined = "$title: $message"
        Log.e(TAG, "Template auto-generation failed for $rootUri: $combined")
        prefs.edit().putString(key(rootUri), combined).apply()
        if (!permissionChecker.isGranted(appContext)) {
            return
        }
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext).notify(notificationId(rootUri), notification)
    }

    override fun loadLastFailure(rootUri: String): String? = prefs.getString(key(rootUri), null)

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.template_auto_generation_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.template_auto_generation_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun key(rootUri: String): String = "template_auto_generation_failure_${stableHash(rootUri)}"

    private fun notificationId(rootUri: String): Int = stableHash(rootUri).take(8).toLong(16).toInt()

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "TemplateAutoGen"
        const val CHANNEL_ID = "template_auto_generation_failures"
    }
}
