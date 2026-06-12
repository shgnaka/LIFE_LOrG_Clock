package com.example.orgclock.desktop

import com.example.orgclock.template.SharedTemplateRevision
import com.example.orgclock.template.SharedTemplateStore
import com.example.orgclock.template.TemplatePushResult
import kotlinx.datetime.Clock
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.io.IOException
import java.security.MessageDigest
import java.util.prefs.Preferences
import kotlin.io.path.exists
import kotlin.io.path.readText

class DesktopSharedTemplateStore(
    private val rootPath: Path,
    private val deviceId: String,
    private val preferences: Preferences = Preferences.userRoot().node(PREFERENCES_NODE),
    private val templatePathProvider: () -> Path = { rootPath.resolve(".orgclock-template.org") },
) : SharedTemplateStore {
    override suspend fun readRevision(): Result<SharedTemplateRevision?> = runCatching {
        val templatePath = templatePath()
        val prefix = prefix(templatePath)
        if (!templatePath.exists()) return@runCatching null
        val content = canonicalContent(templatePath.readText(StandardCharsets.UTF_8))
        val contentHash = stableHash(content)
        val storedHash = preferences.get("${prefix}content_hash", null)
        val storedRevision = preferences.get("${prefix}revision_id", null)
        if (storedHash == contentHash && storedRevision != null) {
            revisionFromPreferences(prefix, content, contentHash, storedRevision)
        } else {
            val revisionId = "sha256:$contentHash"
            persistMetadata(
                prefix,
                revisionId,
                storedRevision,
                contentHash,
                Clock.System.now().toString(),
                deviceId,
            )
            revisionFromPreferences(prefix, content, contentHash, revisionId)
        }
    }

    override suspend fun writeRevision(
        revision: SharedTemplateRevision,
        expectedCurrentRevisionId: String?,
    ): Result<TemplatePushResult> = runCatching {
        val templatePath = templatePath()
        val prefix = prefix(templatePath)
        val current = readRevision().getOrThrow()
        if (current?.revisionId != expectedCurrentRevisionId) {
            return@runCatching TemplatePushResult.Conflict(current?.revisionId, revision.revisionId)
        }
        val content = canonicalContent(revision.content)
        require(stableHash(content) == revision.contentHash) { "Template content hash mismatch" }
        atomicWriteText(templatePath, content)
        persistMetadata(
            prefix,
            revision.revisionId,
            revision.parentRevisionId,
            revision.contentHash,
            revision.updatedAt.toString(),
            revision.updatedByDeviceId,
        )
        TemplatePushResult.Accepted(revision.revisionId)
    }

    private fun templatePath(): Path {
        val root = rootPath.toRealPath()
        val requested = templatePathProvider().toAbsolutePath().normalize()
        val path = if (requested.exists()) {
            requested.toRealPath()
        } else {
            requested.parent.toRealPath().resolve(requested.fileName)
        }
        require(path.startsWith(root)) {
            "Shared template must be inside the org root"
        }
        return path
    }

    private fun atomicWriteText(path: Path, content: String) {
        val temp = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(
                temp,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (atomicError: IOException) {
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                } catch (fallbackError: IOException) {
                    fallbackError.addSuppressed(atomicError)
                    throw fallbackError
                }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun revisionFromPreferences(
        prefix: String,
        content: String,
        contentHash: String,
        revisionId: String,
    ) = SharedTemplateRevision(
        revisionId = revisionId,
        parentRevisionId = preferences.get("${prefix}parent_revision_id", null),
        content = content,
        contentHash = contentHash,
        updatedAt = kotlinx.datetime.Instant.parse(
            preferences.get("${prefix}updated_at", Clock.System.now().toString()),
        ),
        updatedByDeviceId = preferences.get("${prefix}updated_by", deviceId),
    )

    private fun persistMetadata(
        prefix: String,
        revisionId: String,
        parentRevisionId: String?,
        contentHash: String,
        updatedAt: String,
        updatedBy: String,
    ) {
        preferences.put("${prefix}revision_id", revisionId)
        parentRevisionId?.let { preferences.put("${prefix}parent_revision_id", it) }
            ?: preferences.remove("${prefix}parent_revision_id")
        preferences.put("${prefix}content_hash", contentHash)
        preferences.put("${prefix}updated_at", updatedAt)
        preferences.put("${prefix}updated_by", updatedBy)
        preferences.flush()
    }

    private fun stableHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun canonicalContent(value: String): String =
        value.replace("\r\n", "\n").removeSuffix("\n")

    private fun prefix(templatePath: Path): String =
        "tpl_${stableHash(templatePath.toAbsolutePath().normalize().toString()).take(PREFERENCE_HASH_CHARS)}_"

    private companion object {
        const val PREFERENCES_NODE = "com/example/orgclock/desktop/template_sharing"
        const val PREFERENCE_HASH_CHARS = 16
    }
}
