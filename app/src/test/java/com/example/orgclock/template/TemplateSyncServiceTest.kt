package com.example.orgclock.template

import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.data.SaveResult
import com.example.orgclock.model.OrgDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TemplateSyncServiceTest {
    @Test
    fun syncFromFile_updatesExplicitTemplateReference() = runTest {
        val repository = mock(SafOrgRepository::class.java)
        doReturn(
            Result.success(
                OrgDocument(
                    date = kotlinx.datetime.LocalDate(2026, 3, 12),
                    lines = listOf("* Work", "** Seed :TPL:", "Body"),
                    hash = "source-hash",
                ),
            ),
        ).`when`(repository).loadFile("file-1")
        doReturn(
            Result.success(
                OrgDocument(
                    date = kotlinx.datetime.LocalDate(2026, 3, 12),
                    lines = emptyList(),
                    hash = "",
                ),
            ),
        ).`when`(repository).loadTemplate("content://orgclock/root/template-source")
        `when`(
            repository.saveTemplate(
                anyValue(),
                eqValue(""),
                eqValue("content://orgclock/root/template-source"),
            ),
        ).thenReturn(SaveResult.Success)
        val service = TemplateSyncService(
            repository = repository,
            templateFileUriProvider = { "content://orgclock/root/template-source" },
        )

        val result = service.syncFromFile("file-1")

        assertTrue(result.getOrThrow())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return null as T
    }

    private fun <T> eqValue(value: T): T {
        org.mockito.ArgumentMatchers.eq(value)
        return value
    }
}
