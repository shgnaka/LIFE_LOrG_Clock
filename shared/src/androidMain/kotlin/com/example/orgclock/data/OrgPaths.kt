package com.example.orgclock.data

import kotlinx.datetime.LocalDate

object OrgPaths {
    fun dailyFileName(date: LocalDate): String = "${date}.org"
}
