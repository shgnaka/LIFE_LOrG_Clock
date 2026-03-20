@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.example.orgclock.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.example.orgclock.model.OrgDocument
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSString
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

class IosFileOrgRepository : ClockRepository {
    private val fileManager = NSFileManager.defaultManager
    private val rootPath: String by lazy { ensureRootPath() }

    override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> = runCatching {
        val names = fileManager.contentsOfDirectoryAtPath(rootPath, error = null)
            ?.filterIsInstance<String>()
            .orEmpty()
            .filter { OrgFileNames.isVisibleOrgFileName(it) }

        names.map { name ->
            val path = "$rootPath/$name"
            val attrs = fileManager.attributesOfItemAtPath(path, error = null)
            val modified = (attrs?.get(NSFileModificationDate) as? platform.Foundation.NSDate)
                ?.let { date -> Instant.fromEpochMilliseconds((date.timeIntervalSince1970 * 1000.0).toLong()) }
            OrgFileEntry(
                fileId = path,
                displayName = name,
                modifiedAt = modified,
            )
        }.sortedByDescending { it.modifiedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
    }

    override suspend fun loadFile(fileId: String): Result<OrgDocument> = runCatching {
        validateFileId(fileId)
        val rawText = readText(fileId) ?: throw IllegalArgumentException("File not found")
        val lines = parseLines(rawText)
        OrgDocument(
            date = parseDateFromFileName(fileName(fileId)),
            lines = lines,
            hash = contentHash(canonicalText(lines)),
        )
    }

    override suspend fun saveFile(
        fileId: String,
        lines: List<String>,
        expectedHash: String,
        writeIntent: FileWriteIntent,
    ): SaveResult {
        validateFileId(fileId)
        if (!fileManager.fileExistsAtPath(fileId)) {
            return SaveResult.ValidationError("File not found")
        }

        val existingRaw = readText(fileId) ?: return SaveResult.IoError("Cannot read file")
        val existingLines = parseLines(existingRaw)
        val existingHash = contentHash(canonicalText(existingLines))
        if (existingHash != expectedHash) {
            return SaveResult.Conflict("File changed by another process.")
        }

        val outputText = formatOutputText(
            lines = lines,
            lineSeparator = detectLineSeparator(existingRaw),
            trailingNewline = existingRaw.endsWith('\n'),
        )
        if (!writeText(fileId, outputText)) return SaveResult.IoError("Failed to write file")
        val roundTripRaw = readText(fileId) ?: return SaveResult.IoError("Cannot verify written file")
        if (canonicalText(parseLines(roundTripRaw)) != canonicalText(lines)) {
            return SaveResult.RoundTripMismatch("Saved file contents changed after round-trip verification")
        }
        return SaveResult.Success
    }

    override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> = runCatching {
        val path = dailyPath(date)
        val rawText = readText(path)
        val lines = if (rawText == null) emptyList() else parseLines(rawText)
        OrgDocument(
            date = date,
            lines = lines,
            hash = contentHash(canonicalText(lines)),
        )
    }

    override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult {
        val path = dailyPath(date)
        val existingRaw = readText(path)
        val existingLines = existingRaw?.let(::parseLines).orEmpty()
        val existingHash = contentHash(canonicalText(existingLines))
        if (existingHash != expectedHash) {
            return SaveResult.Conflict("File changed by another process.")
        }

        val lineSeparator = existingRaw?.let(::detectLineSeparator) ?: "\n"
        val trailingNewline = existingRaw?.endsWith('\n') ?: false
        val outputText = formatOutputText(lines, lineSeparator, trailingNewline)
        if (!writeText(path, outputText)) return SaveResult.IoError("Failed to write file")
        val roundTripRaw = readText(path) ?: return SaveResult.IoError("Cannot verify written file")
        if (canonicalText(parseLines(roundTripRaw)) != canonicalText(lines)) {
            return SaveResult.RoundTripMismatch("Saved file contents changed after round-trip verification")
        }
        return SaveResult.Success
    }

    private fun ensureRootPath(): String {
        val candidates = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val documents = candidates.firstOrNull() as? String
            ?: error("Cannot resolve iOS documents directory")
        val root = "$documents/OrgClock"
        fileManager.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null, error = null)
        return root
    }

    private fun dailyPath(date: LocalDate): String = "$rootPath/${date}.org"

    private fun validateFileId(fileId: String) {
        if (!fileId.startsWith("$rootPath/")) {
            throw IllegalArgumentException("File is outside OrgClock root")
        }
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    private fun readText(path: String): String? {
        if (!fileManager.fileExistsAtPath(path)) return null
        return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) as String?
    }

    private fun writeText(path: String, text: String): Boolean {
        val bytes = text.encodeToByteArray()
        val file = fopen(path, "wb") ?: return false
        val wroteAll = try {
            if (bytes.isEmpty()) {
                true
            } else {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file) == bytes.size.toULong()
                }
            }
        } finally {
            fclose(file)
        }
        return wroteAll
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

    private fun parseDateFromFileName(name: String): LocalDate {
        val raw = name.removeSuffix(".org")
        val parsed = raw.split("-")
        if (parsed.size != 3) return today()
        val year = parsed[0].toIntOrNull() ?: return today()
        val month = parsed[1].toIntOrNull() ?: return today()
        val day = parsed[2].toIntOrNull() ?: return today()
        return runCatching { LocalDate(year, month, day) }.getOrElse { today() }
    }

    private fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun contentHash(text: String): String {
        val bytes = text.encodeToByteArray()
        var hash = FNV_OFFSET_BASIS
        bytes.forEach { byte ->
            hash = hash xor byte.toUByte().toULong()
            hash *= FNV_PRIME
        }
        return hash.toString(16).padStart(16, '0')
    }

    private companion object {
        const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325u
        const val FNV_PRIME: ULong = 0x100000001b3u
    }
}
