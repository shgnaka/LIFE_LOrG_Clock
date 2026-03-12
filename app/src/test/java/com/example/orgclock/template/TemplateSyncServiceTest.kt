package com.example.orgclock.template

import com.example.orgclock.data.SaveResult
import com.example.orgclock.model.OrgDocument
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateSyncServiceTest {
    @Test
    fun syncFromFile_mergesTaggedSectionsAndKeepsExistingOrder() = runTest {
        val repository = FakeTemplateSyncRepository(
            sourceLines = listOf(
                "* Work",
                "** Project A :TPL:",
                "A body",
                "* Personal :TPL:",
                "B body",
            ),
            templateLines = listOf(
                "* Personal :TPL:",
                "old body",
                "",
                "* Archive :TPL:",
                "kept",
            ),
        )

        val result = TemplateSyncService(repository).syncFromFile("f1")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "* Personal :TPL:",
                "B body",
                "",
                "* Archive :TPL:",
                "kept",
                "",
                "** Project A :TPL:",
                "A body",
            ),
            repository.savedLines,
        )
    }

    @Test
    fun syncFromFile_ignoresNestedTplWhenParentAlreadyTagged() = runTest {
        val repository = FakeTemplateSyncRepository(
            sourceLines = listOf(
                "* Parent :TPL:",
                "** Child :TPL:",
                "child body",
            ),
            templateLines = emptyList(),
        )

        val result = TemplateSyncService(repository).syncFromFile("f1")

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "* Parent :TPL:",
                "** Child :TPL:",
                "child body",
            ),
            repository.savedLines,
        )
    }

    private class FakeTemplateSyncRepository(
        sourceLines: List<String>,
        templateLines: List<String>,
    ) : TemplateSyncRepository {
        private val source = OrgDocument(date = LocalDate(2026, 3, 12), lines = sourceLines, hash = "source")
        private var template = OrgDocument(date = LocalDate(2026, 3, 12), lines = templateLines, hash = "template-hash")
        var savedLines: List<String>? = null

        override suspend fun loadFile(fileId: String): Result<OrgDocument> = Result.success(source)

        override suspend fun loadTemplate(): Result<OrgDocument> = Result.success(template)

        override suspend fun saveTemplate(lines: List<String>, expectedHash: String): SaveResult {
            savedLines = lines
            template = template.copy(lines = lines, hash = "updated")
            return SaveResult.Success
        }
    }
}
