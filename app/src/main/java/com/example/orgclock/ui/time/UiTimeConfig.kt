package com.example.orgclock.ui.time

const val TIME_EDIT_MINUTE_STEP = 5
const val RUNNING_PANEL_TICK_MS = 60_000L

fun minuteStepOptions(): List<Int> = (0..(60 - TIME_EDIT_MINUTE_STEP) step TIME_EDIT_MINUTE_STEP).toList()

fun normalizeMinuteToStep(minute: Int): Int {
    require(minute in 0..59) { "Minute must be between 0 and 59: $minute" }
    val rounded = ((minute + (TIME_EDIT_MINUTE_STEP / 2)) / TIME_EDIT_MINUTE_STEP) * TIME_EDIT_MINUTE_STEP
    return rounded.coerceAtMost(60 - TIME_EDIT_MINUTE_STEP)
}
