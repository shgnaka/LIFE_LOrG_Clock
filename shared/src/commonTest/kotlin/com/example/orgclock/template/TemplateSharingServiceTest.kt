package com.example.orgclock.template

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateSharingServiceTest {
    @Test
    fun emptyLocal_downloadsRemoteTemplate() = runTest {
        val remote = revision("remote-1", null)
        val localStore = MemoryTemplateStore(null)
        val transport = MemoryTemplateTransport(remote)

        val outcome = TemplateSharingService(localStore, transport, "local", "remote")
            .synchronize().getOrThrow()

        assertEquals(TemplateSyncOutcome.Downloaded("remote-1"), outcome)
        assertEquals(remote, localStore.current)
    }

    @Test
    fun localChild_uploadsAsFastForward() = runTest {
        val remote = revision("base", null)
        val local = revision("local-2", "base")
        val transport = MemoryTemplateTransport(remote)

        val outcome = TemplateSharingService(MemoryTemplateStore(local), transport, "local", "remote")
            .synchronize().getOrThrow()

        assertEquals(TemplateSyncOutcome.Uploaded("local-2"), outcome)
        assertEquals(local, transport.current)
    }

    @Test
    fun divergentChildren_reportConflictWithoutOverwrite() = runTest {
        val local = revision("local-2", "base")
        val remote = revision("remote-2", "base")
        val localStore = MemoryTemplateStore(local)
        val transport = MemoryTemplateTransport(remote)

        val outcome = TemplateSharingService(localStore, transport, "local", "remote")
            .synchronize().getOrThrow()

        assertEquals(TemplateSyncOutcome.Conflict("local-2", "remote-2"), outcome)
        assertEquals(local, localStore.current)
        assertEquals(remote, transport.current)
    }

    private fun revision(id: String, parent: String?) = SharedTemplateRevision(
        revisionId = id,
        parentRevisionId = parent,
        content = "* $id\n",
        contentHash = "hash-$id",
        updatedAt = Instant.parse("2026-06-12T00:00:00Z"),
        updatedByDeviceId = "device",
    )
}

private class MemoryTemplateStore(
    var current: SharedTemplateRevision?,
) : SharedTemplateStore {
    override suspend fun readRevision(): Result<SharedTemplateRevision?> = Result.success(current)

    override suspend fun writeRevision(
        revision: SharedTemplateRevision,
        expectedCurrentRevisionId: String?,
    ): Result<TemplatePushResult> {
        if (current?.revisionId != expectedCurrentRevisionId) {
            return Result.success(TemplatePushResult.Conflict(current?.revisionId, revision.revisionId))
        }
        current = revision
        return Result.success(TemplatePushResult.Accepted(revision.revisionId))
    }
}

private class MemoryTemplateTransport(
    var current: SharedTemplateRevision?,
) : TemplateSyncTransport {
    override suspend fun fetchTemplate(request: TemplateFetchRequest): TemplateFetchResponse =
        TemplateFetchResponse(request.targetPeerId, request.sourcePeerId, current)

    override suspend fun pushTemplate(request: TemplatePushRequest): TemplatePushResult {
        if (current?.revisionId != request.expectedCurrentRevisionId) {
            return TemplatePushResult.Conflict(current?.revisionId, request.revision.revisionId)
        }
        current = request.revision
        return TemplatePushResult.Accepted(request.revision.revisionId)
    }
}
