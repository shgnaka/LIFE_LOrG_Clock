package com.example.orgclock.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.orgclock.model.OrgDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class SafOrgRepository(
    private val context: Context,
    private val backupPolicy: BackupPolicyConfig = BackupPolicyConfig(),
) : OrgRepository {
    private val resolver: ContentResolver = context.contentResolver
    private var root: DocumentFile? = null
    private val lastClockBackupByFileId = mutableMapOf<String, Long>()

    override suspend fun openRoot(uri: Uri): Result<RootAccess> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val rootDoc = DocumentFile.fromTreeUri(context, uri)
                ?: throw IllegalArgumentException("Invalid tree uri")
            require(rootDoc.isDirectory) { "Selected uri must be a directory." }
            root = rootDoc
            RootAccess(uri, rootDoc.name ?: "org")
        }
    }

    override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val rootDoc = root ?: throw IllegalStateException("Root is not opened")
            val name = OrgPaths.dailyFileName(date)
            val file = rootDoc.findFile(name)
            val rawText = if (file == null) {
                null
            } else {
                readText(file.uri)
            }
            val lines = if (rawText == null) {
                emptyList()
            } else {
                parseLines(rawText)
            }
            val canonicalText = canonicalText(lines)
            OrgDocument(date = date, lines = lines, hash = hash(canonicalText))
        }
    }

    override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val rootDoc = root ?: return@withContext SaveResult.ValidationError("Root is not opened")
                val name = OrgPaths.dailyFileName(date)
                val existing = rootDoc.findFile(name)
                val existingRawText = existing?.let { readText(it.uri) }
                val existingLines = existingRawText?.let { parseLines(it) } ?: emptyList()

                val existingHash = hash(canonicalText(existingLines))
                if (existingHash != expectedHash) {
                    return@withContext SaveResult.Conflict("File changed by another process.")
                }

                createBackupIfNeeded(rootDoc, name, existingRawText.orEmpty())

                val target = existing ?: createDocumentExactName(rootDoc, name)
                    ?: return@withContext SaveResult.IoError("Failed to create file: $name")
                val lineSeparator = existingRawText?.let { detectLineSeparator(it) } ?: "\n"
                val keepTrailingNewline = existingRawText?.endsWith('\n') ?: false
                val outputText = formatOutputText(lines, lineSeparator, keepTrailingNewline)
                resolver.openOutputStream(target.uri, "wt").use { output ->
                    requireNotNull(output) { "Cannot open output file: $name" }
                    val writer = OutputStreamWriter(output)
                    writer.write(outputText)
                    writer.flush()
                }
                SaveResult.Success
            }.getOrElse {
                SaveResult.IoError(it.message ?: "Unknown I/O error")
            }
        }

    override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val rootDoc = root ?: throw IllegalStateException("Root is not opened")
            rootDoc.listFiles()
                .asSequence()
                .filter { it.isFile }
                .filter { it.name?.endsWith(".org", ignoreCase = true) == true }
                .mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    OrgFileEntry(
                        fileId = file.uri.toString(),
                        displayName = name,
                        modifiedAt = file.lastModified().takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                    )
                }
                .sortedByDescending { it.modifiedAt ?: Instant.EPOCH }
                .toList()
        }
    }

    override suspend fun loadFile(fileId: String): Result<OrgDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val rootDoc = root ?: throw IllegalStateException("Root is not opened")
            val file = resolveFileById(rootDoc, fileId) ?: throw IllegalArgumentException("File not found")
            val rawText = readText(file.uri)
            val lines = parseLines(rawText)
            val date = parseDateFromFileName(file.name)
            OrgDocument(date = date, lines = lines, hash = hash(canonicalText(lines)))
        }
    }

    override suspend fun saveFile(
        fileId: String,
        lines: List<String>,
        expectedHash: String,
        writeIntent: FileWriteIntent,
    ): SaveResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val rootDoc = root ?: return@withContext SaveResult.ValidationError("Root is not opened")
                val target = resolveFileById(rootDoc, fileId)
                    ?: return@withContext SaveResult.ValidationError("File not found")
                val fileName = target.name ?: return@withContext SaveResult.ValidationError("Invalid file name")
                val existingRawText = readText(target.uri)
                val existingLines = parseLines(existingRawText)
                val existingHash = hash(canonicalText(existingLines))
                if (existingHash != expectedHash) {
                    return@withContext SaveResult.Conflict("File changed by another process.")
                }

                val nowMs = System.currentTimeMillis()
                val shouldBackup = shouldCreateBackup(fileId, writeIntent, nowMs)
                if (shouldBackup) {
                    val backupCreated = createBackupIfNeeded(rootDoc, fileName, existingRawText)
                    if (backupCreated && writeIntent == FileWriteIntent.ClockMutation) {
                        synchronized(lastClockBackupByFileId) {
                            lastClockBackupByFileId[fileId] = nowMs
                        }
                    }
                }

                val lineSeparator = detectLineSeparator(existingRawText)
                val keepTrailingNewline = existingRawText.endsWith('\n')
                val outputText = formatOutputText(lines, lineSeparator, keepTrailingNewline)

                resolver.openOutputStream(target.uri, "wt").use { output ->
                    requireNotNull(output) { "Cannot open output file: $fileName" }
                    val writer = OutputStreamWriter(output)
                    writer.write(outputText)
                    writer.flush()
                }

                SaveResult.Success
            }.getOrElse {
                SaveResult.IoError(it.message ?: "Unknown I/O error")
            }
        }

    private fun shouldCreateBackup(fileId: String, writeIntent: FileWriteIntent, nowMs: Long): Boolean {
        if (writeIntent == FileWriteIntent.UserEdit) return true
        val lastClockBackupAt = synchronized(lastClockBackupByFileId) {
            lastClockBackupByFileId[fileId]
        }
        return shouldCreateClockBackup(
            lastClockBackupAtMs = lastClockBackupAt,
            nowMs = nowMs,
            clockBackupIntervalMs = backupPolicy.clockBackupIntervalMs,
        )
    }

    private fun createBackupIfNeeded(rootDoc: DocumentFile, fileName: String, existingRawText: String): Boolean {
        if (existingRawText.isEmpty()) return false

        val stamp = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val backupName = ".${fileName}.bak.$stamp"
        val backup = createDocumentExactName(rootDoc, backupName) ?: return false

        resolver.openOutputStream(backup.uri, "wt").use { output ->
            if (output != null) {
                val writer = OutputStreamWriter(output)
                writer.write(existingRawText)
                writer.flush()
            }
        }

        val backups = rootDoc.listFiles()
            .filter { it.name?.startsWith(".${fileName}.bak.") == true }
            .sortedByDescending { it.name }

        backupsToPrune(backups, backupPolicy.backupGenerations).forEach { it.delete() }
        return true
    }

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readText(uri: Uri): String {
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open file: $uri" }
            return input.bufferedReader().use { it.readText() }
        }
    }

    private fun parseLines(rawText: String): List<String> {
        if (rawText.isEmpty()) return emptyList()
        return rawText
            .split('\n')
            .map { it.removeSuffix("\r") }
            .let { lines ->
                if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            }
    }

    private fun canonicalText(lines: List<String>): String = lines.joinToString("\n")

    private fun detectLineSeparator(rawText: String): String {
        return if (rawText.contains("\r\n")) "\r\n" else "\n"
    }

    private fun formatOutputText(lines: List<String>, lineSeparator: String, trailingNewline: Boolean): String {
        val body = lines.joinToString(lineSeparator)
        if (body.isEmpty()) return body
        return if (trailingNewline) body + lineSeparator else body
    }

    private fun createDocumentExactName(rootDoc: DocumentFile, displayName: String): DocumentFile? {
        val createdUri = DocumentsContract.createDocument(
            resolver,
            rootDoc.uri,
            "application/octet-stream",
            displayName,
        ) ?: return null
        return DocumentFile.fromSingleUri(context, createdUri)
    }

    private fun resolveFileById(rootDoc: DocumentFile, fileId: String): DocumentFile? {
        val uri = runCatching { Uri.parse(fileId) }.getOrNull() ?: return null
        if (!isUriUnderRoot(rootDoc.uri, uri)) return null
        val file = DocumentFile.fromSingleUri(context, uri) ?: return null
        return file.takeIf { it.exists() && it.isFile }
    }

    private fun isUriUnderRoot(rootUri: Uri, fileUri: Uri): Boolean {
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }
            .getOrNull()
            ?: return false
        val fileDocumentId = runCatching { DocumentsContract.getDocumentId(fileUri) }
            .getOrNull()
            ?: return false
        return fileDocumentId == rootDocumentId || fileDocumentId.startsWith("$rootDocumentId/")
    }

    private fun parseDateFromFileName(name: String?): LocalDate {
        if (name == null) return LocalDate.now(ZoneId.systemDefault())
        val raw = name.removeSuffix(".org")
        return runCatching { LocalDate.parse(raw) }
            .getOrElse { LocalDate.now(ZoneId.systemDefault()) }
    }

    companion object
}
