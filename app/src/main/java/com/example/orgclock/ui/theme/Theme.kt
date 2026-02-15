package com.example.orgclock.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val OrgClockLightColors: ColorScheme = lightColorScheme(
    primary = CalmAccent,
    onPrimary = CalmOnAccent,
    secondary = CalmAccent,
    onSecondary = CalmOnAccent,
    background = CalmBackground,
    onBackground = CalmTextPrimary,
    surface = CalmSurface,
    onSurface = CalmTextPrimary,
    surfaceVariant = CalmSurfaceAlt,
    onSurfaceVariant = CalmTextSecondary,
    outline = CalmBorder,
    error = StateErrorFg,
    onError = CalmOnAccent,
)

@Composable
fun OrgClockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OrgClockLightColors,
        typography = OrgClockTypography,
        content = content,
    )
}
