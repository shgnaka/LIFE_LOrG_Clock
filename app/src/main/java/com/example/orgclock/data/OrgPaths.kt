package com.example.orgclock.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object OrgPaths {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun dailyFileName(date: LocalDate): String = "${date.format(dateFormatter)}.org"
}
