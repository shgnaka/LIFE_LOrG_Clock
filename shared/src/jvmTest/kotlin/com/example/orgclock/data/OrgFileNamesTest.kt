package com.example.orgclock.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrgFileNamesTest {
    @Test
    fun isVisibleOrgFileName_excludesTemplate() {
        assertFalse(OrgFileNames.isVisibleOrgFileName(".orgclock-template.org"))
        assertTrue(OrgFileNames.isVisibleOrgFileName("2026-03-11.org"))
    }
}
