package com.example.orgclock.data

import com.example.orgclock.model.OrgDocument
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DesktopFileOrgRepository(
    rootDirectory: Path,
    private val backupPolicy: BackupPolicyConfig = BackupPolicyConfig(),
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
) : ClockRepository {
    private val rootPath: Path = rootDirectory.absolute().normalize().createDirectories()
    private val lastClockBackupByFileId = mutableMapOf<String, Long>()

    init {
        require(rootPath.exists() && rootPath.isDirectory()) { "Root path must be a directory" }
    }

    override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> = runCatching {
        listRootOrgEntries { OrgFileNames.isVisibleOrgFileName(it.name) }
    }

    override suspend fun listTemplateCandidateFiles(): Result<List<OrgFileEntry>> = runCatching {
        listRootOrgEntries { it.name.endsWith(".org", ignoreCase = true) }
            .sortedWith(
                compareByDescending<OrgFileEntry> { OrgFileNames.isTemplateFileName(it.displayName) }
                    .thenByDescending { it.modifiedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE },
            )
    }

    override suspend fun loadFile(fileId: String): Result<OrgDocument> = runCatching {
        val path = resolveExistingFile(fileId)
        val rawText = path.readText(StandardCharsets.UTF_8)
        val lines = parseLines(rawText)
        OrgDocument(
            date = parseDateFromFileName(path.name),
            lines = lines,
            hash = hash(canonicalText(lines)),
        )
    }

    override suspend fun saveFile(
        fileId: String,
        lines: List<String>,
        expectedHash: String,
        writeIntent: FileWriteIntent,
    ): SaveResult {
        val path = runCatching { resolveExistingFile(fileId) }
            .getOrElse { return SaveResult.ValidationError(it.message ?: "Invalid file") }
        return saveToPath(
            path = path,
            lines = lines,
            expectedHash = expectedHash,
            createIfMissing = false,
            writeIntent = writeIntent,
        )
    }

    override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> = runCatching {
        val path = dailyPath(date)
        val rawText = if (path.exists()) path.readText(StandardCharsets.UTF_8) else null
        val lines = rawText?.let(::parseLines).orEmpty()
        OrgDocument(
            date = date,
            lines = lines,
            hash = hash(canonicalText(lines)),
        )
    }

    override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult =
        saveToPath(
            path = dailyPath(date),
            lines = lines,
            expectedHash = expectedHash,
            createIfMissing = true,
            writeIntent = FileWriteIntent.UserEdit,
        )

    private fun saveToPath(
        path: Path,
        lines: List<String>,
        expectedHash: String,
        createIfMissing: Boolean,
        writeIntent: FileWriteIntent,
    ): SaveResult {
        val normalizedPath = path.toAbsolutePath().normalize()
        if (!isUnderRoot(normalizedPath)) {
            return SaveResult.ValidationError("File is outside repository root")
        }
        if (!normalizedPath.exists() && !createIfMissing) {
            return SaveResult.ValidationError("File not found")
        }

        return runCatching {
            val existingRawText = if (normalizedPath.exists()) {
                normalizedPath.readText(StandardCharsets.UTF_8)
            } else {
                null
            }
            val existingLines = existingRawText?.let(::parseLines).orEmpty()
            val existingHash = hash(canonicalText(existingLines))
            if (existingHash != expectedHash) {
                return SaveResult.Conflict("File changed by another process.")
            }

            val nowMs = nowMsProvider()
            val shouldBackup = if (createIfMissing) {
                true
            } else {
                shouldCreateBackup(
                    fileId = normalizedPath.absolutePathString(),
                    writeIntent = writeIntent,
                    nowMs = nowMs,
                )
            }
            if (shouldBackup) {
                val backupCreated = createBackupIfNeeded(normalizedPath, existingRawText, nowMs)
                if (backupCreated && writeIntent == FileWriteIntent.ClockMutation) {
                    synchronized(lastClockBackupByFileId) {
                        lastClockBackupByFileId[normalizedPath.absolutePathString()] = nowMs
                    }
                }
            }

            val lineSeparator = existingRawText?.let(::detectLineSeparator) ?: "\n"
            val trailingNewline = existingRawText?.endsWith('\n') ?: false
            val outputText = formatOutputText(lines, lineSeparator, trailingNewline)
            Files.writeString(
                normalizedPath,
                outputText,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
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

    private fun createBackupIfNeeded(path: Path, existingRawText: String?, nowMs: Long): Boolean {
        if (existingRawText.isNullOrEmpty()) return false

        val backupName = ".${path.name}.bak.${backupStamp(nowMs)}"
        val backupPath = path.parent.resolve(backupName)
        backupPath.writeText(existingRawText, StandardCharsets.UTF_8)

        val backups = path.parent.listDirectoryEntries(".${path.name}.bak.*")
            .filter { it.isRegularFile() }
            .sortedByDescending { it.name }

        backupsToPrune(backups, backupPolicy.backupGenerations).forEach { Files.deleteIfExists(it) }
        return true
    }

    private fun resolveExistingFile(fileId: String): Path {
        val path = runCatching { Path.of(fileId).toAbsolutePath().normalize() }
            .getOrElse { throw IllegalArgumentException("Invalid file id") }
        require(isUnderRoot(path)) { "File is outside repository root" }
        require(path.exists() && path.isRegularFile()) { "File not found" }
        return path
    }

    private fun isUnderRoot(path: Path): Boolean = path.startsWith(rootPath)

    private fun dailyPath(date: LocalDate): Path = rootPath.resolve("$date.org")

    private fun listRootOrgEntries(include: (Path) -> Boolean): List<OrgFileEntry> {
        return rootPath.listDirectoryEntries("*.org")
            .filter { it.isRegularFile() }
            .filter(include)
            .map { path ->
                OrgFileEntry(
                    fileId = path.absolutePathString(),
                    displayName = path.name,
                    modifiedAt = path.getLastModifiedTime().toMillis().takeIf { it > 0 }?.let(Instant::fromEpochMilliseconds),
                )
            }
            .sortedByDescending { it.modifiedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
    }

    private fun parseLines(rawText: String): List<String> {
        if (rawText.isEmpty()) return emptyList()
        return rawText.split('\n')
            .map { it.removeSuffix("\r") }
            .let { lines -> if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines }
    }

    private fun canonicalText(lines: List<String>): String = lines.joinToString("\n")

    private fun detectLineSeparator(rawText: String): String = if (rawText.contains("\r\n")) "\r\n" else "\n"

    private fun formatOutputText(lines: List<String>, lineSeparator: String, trailingNewline: Boolean): String {
        val body = lines.joinToString(lineSeparator)
        if (body.isEmpty()) return body
        return if (trailingNewline) body + lineSeparator else body
    }

    private fun parseDateFromFileName(name: String): LocalDate =
        runCatching { LocalDate.parse(name.removeSuffix(".org")) }
            .getOrElse { today() }

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun backupStamp(nowMs: Long): String =
        java.time.Instant.ofEpochMilli(nowMs)
            .atZone(ZoneId.systemDefault())
            .format(BACKUP_STAMP_FORMAT)

    private fun today(): LocalDate {
        val current = java.time.LocalDate.now(ZoneId.systemDefault())
        return LocalDate(current.year, current.monthValue, current.dayOfMonth)
    }

    private companion object {
        val BACKUP_STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
