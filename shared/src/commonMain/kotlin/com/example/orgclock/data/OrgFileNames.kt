package com.example.orgclock.data

object OrgFileNames {
    const val TEMPLATE_FILE_NAME = ".orgclock-template.org"

    fun isTemplateFileName(name: String): Boolean = name.equals(TEMPLATE_FILE_NAME, ignoreCase = true)

    fun isVisibleOrgFileName(name: String): Boolean {
        return name.endsWith(".org", ignoreCase = true) && !isTemplateFileName(name)
    }
}
