package com.example.orgclock.data

import com.example.orgclock.template.TemplateAvailability
import com.example.orgclock.template.TemplateFileStatus
import com.example.orgclock.template.TemplateGenerationFailureKind
import com.example.orgclock.template.TemplateGenerationResult
import com.example.orgclock.template.TemplateReferenceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafOrgRepositoryTemplateGenerationTest {
    @Test
    fun expectedHashForMissingDocument_matchesEmptyContentHash() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            expectedHashForMissingDocument(),
        )
    }

    @Test
    fun saveResultConflict_isReportedAsFailureInsteadOfAlreadyExisting() {
        val templateStatus = TemplateFileStatus(
            availability = TemplateAvailability.Available,
            referenceMode = TemplateReferenceMode.LegacyHiddenFile,
        )

        val result = saveResultToTemplateGenerationResult(
            save = SaveResult.Conflict("File changed by another process."),
            templateStatus = templateStatus,
        )

        assertTrue(result is TemplateGenerationResult.Failed)
        result as TemplateGenerationResult.Failed
        assertEquals("File changed by another process.", result.reason)
        assertEquals(TemplateGenerationFailureKind.SaveFailed, result.kind)
        assertEquals(templateStatus, result.templateStatus)
    }
}
