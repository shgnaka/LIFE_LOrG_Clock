package com.example.orgclock.data

import kotlinx.datetime.LocalDate

object OrgPaths {
    fun dailyFileName(date: LocalDate): String = "${date}.org"
    fun templateFileName(): String = ".template.org"
    fun legacyTemplateFileName(): String = "template.org"
}
