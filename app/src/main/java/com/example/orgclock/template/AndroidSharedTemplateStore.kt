package com.example.orgclock.template

import android.content.SharedPreferences
import com.example.orgclock.data.SafOrgRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AndroidSharedTemplateStore(
    private val repository: SafOrgRepository,
    private val preferences: SharedPreferences,
    private val rootUriProvider: () -> String?,
    private val templateFileUriProvider: () -> String?,
    private val deviceIdProvider: () -> String,
) : SharedTemplateStore {
    override suspend fun readRevision(): Result<SharedTemplateRevision?> = runCatching {
        val rootUri = rootUriProvider() ?: error("org root is not configured")
        val templateFileUri = templateFileUriProvider()
        val status = repository.inspectTemplateFile(templateFileUri).getOrThrow()
        if (status.availability == TemplateAvailability.Missing) return@runCatching null
        require(status.availability == TemplateAvailability.Available) {
            status.detailMessage ?: "Template is not readable"
        }
        val template = repository.loadTemplate(templateFileUri).getOrThrow()
        val content = template.lines.joinToString("\n")
        val contentHash = stableHash(content)
        val prefix = prefix(rootUri, templateFileUri)
        val storedHash = preferences.getString("${prefix}content_hash", null)
        val storedRevision = preferences.getString("${prefix}revision_id", null)
        val revisionId = if (storedHash == contentHash && storedRevision != null) {
            storedRevision
        } else {
            "sha256:$contentHash".also {
                persistMetadata(prefix, it, storedRevision, contentHash, Clock.System.now(), deviceIdProvider())
            }
        }
        SharedTemplateRevision(
            revisionId = revisionId,
            parentRevisionId = preferences.getString("${prefix}parent_revision_id", null),
            content = content,
            contentHash = contentHash,
            updatedAt = preferences.getString("${prefix}updated_at", null)
                ?.let(Instant::parse) ?: Clock.System.now(),
            updatedByDeviceId = preferences.getString("${prefix}updated_by", null) ?: deviceIdProvider(),
        )
    }

    override suspend fun writeRevision(
        revision: SharedTemplateRevision,
        expectedCurrentRevisionId: String?,
    ): Result<TemplatePushResult> = runCatching {
        val current = readRevision().getOrThrow()
        if (current?.revisionId != expectedCurrentRevisionId) {
            return@runCatching TemplatePushResult.Conflict(current?.revisionId, revision.revisionId)
        }
        require(stableHash(revision.content) == revision.contentHash) { "Template content hash mismatch" }
        val existing = repository.loadTemplate(templateFileUriProvider()).getOrThrow()
        when (
            val saved = repository.saveTemplate(
                lines = revision.content.split('\n').let { if (it.lastOrNull().isNullOrEmpty()) it.dropLast(1) else it },
                expectedHash = existing.hash,
                templateFileUri = templateFileUriProvider(),
            )
        ) {
            com.example.orgclock.data.SaveResult.Success -> Unit
            is com.example.orgclock.data.SaveResult.Conflict -> error(saved.reason)
            is com.example.orgclock.data.SaveResult.IoError -> error(saved.reason)
            is com.example.orgclock.data.SaveResult.RoundTripMismatch -> error(saved.reason)
            is com.example.orgclock.data.SaveResult.ValidationError -> error(saved.reason)
        }
        val prefix = prefix(
            rootUriProvider() ?: error("org root is not configured"),
            templateFileUriProvider(),
        )
        persistMetadata(
            prefix,
            revision.revisionId,
            revision.parentRevisionId,
            revision.contentHash,
            revision.updatedAt,
            revision.updatedByDeviceId,
        )
        TemplatePushResult.Accepted(revision.revisionId)
    }

    private fun persistMetadata(
        prefix: String,
        revisionId: String,
        parentRevisionId: String?,
        contentHash: String,
        updatedAt: Instant,
        updatedBy: String,
    ) {
        preferences.edit()
            .putString("${prefix}revision_id", revisionId)
            .putString("${prefix}parent_revision_id", parentRevisionId)
            .putString("${prefix}content_hash", contentHash)
            .putString("${prefix}updated_at", updatedAt.toString())
            .putString("${prefix}updated_by", updatedBy)
            .apply()
    }

    private fun prefix(rootUri: String, templateFileUri: String?): String =
        "shared_template_${stableHash("$rootUri\n${templateFileUri.orEmpty()}")}_"

    private fun stableHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
