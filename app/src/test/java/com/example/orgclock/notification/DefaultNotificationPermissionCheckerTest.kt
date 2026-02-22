package com.example.orgclock.notification

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class DefaultNotificationPermissionCheckerTest {
    private val context: Context = Mockito.mock(Context::class.java)

    @Test
    fun sdkBelow33_returnsNotificationsEnabledWhenTrue() {
        val checker = DefaultNotificationPermissionChecker(
            sdkIntProvider = { 32 },
            notificationsEnabledProvider = { true },
            runtimePermissionGrantedProvider = { false },
        )

        assertTrue(checker.isGranted(context))
    }

    @Test
    fun sdkBelow33_returnsNotificationsEnabledWhenFalse() {
        val checker = DefaultNotificationPermissionChecker(
            sdkIntProvider = { 32 },
            notificationsEnabledProvider = { false },
            runtimePermissionGrantedProvider = { true },
        )

        assertFalse(checker.isGranted(context))
    }

    @Test
    fun sdk33OrAbove_requiresRuntimeAndNotificationsEnabled() {
        val checker = DefaultNotificationPermissionChecker(
            sdkIntProvider = { 33 },
            notificationsEnabledProvider = { true },
            runtimePermissionGrantedProvider = { true },
        )

        assertTrue(checker.isGranted(context))
    }

    @Test
    fun sdk33OrAbove_returnsFalseWhenRuntimePermissionDenied() {
        val checker = DefaultNotificationPermissionChecker(
            sdkIntProvider = { 33 },
            notificationsEnabledProvider = { true },
            runtimePermissionGrantedProvider = { false },
        )

        assertFalse(checker.isGranted(context))
    }

    @Test
    fun sdk33OrAbove_returnsFalseWhenNotificationsDisabled() {
        val checker = DefaultNotificationPermissionChecker(
            sdkIntProvider = { 33 },
            notificationsEnabledProvider = { false },
            runtimePermissionGrantedProvider = { true },
        )

        assertFalse(checker.isGranted(context))
    }
}
