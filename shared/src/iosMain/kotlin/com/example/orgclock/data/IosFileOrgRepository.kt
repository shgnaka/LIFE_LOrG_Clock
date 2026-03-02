package com.example.orgclock.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.CommonCrypto.CC_SHA256
import platform.CommonCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSString
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.timeIntervalSince1970

class IosFileOrgRepository : ClockRepository {
    private val fileManager = NSFileManager.defaultManager
    private val rootPath: String by lazy { ensureRootPath() }

    override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> = runCatching {
        val names = fileManager.contentsOfDirectoryAtPath(rootPath, error = null)
            ?.filterIsInstance<String>()
            .orEmpty()
            .filter { it.endsWith(".org", ignoreCase = true) }

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
            hash = sha256(canonicalText(lines)),
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
        val existingHash = sha256(canonicalText(existingLines))
        if (existingHash != expectedHash) {
            return SaveResult.Conflict("File changed by another process.")
        }

        val outputText = formatOutputText(
            lines = lines,
            lineSeparator = detectLineSeparator(existingRaw),
            trailingNewline = existingRaw.endsWith('\n'),
        )
        return if (writeText(fileId, outputText)) SaveResult.Success else SaveResult.IoError("Failed to write file")
    }

    override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> = runCatching {
        val path = dailyPath(date)
        val rawText = readText(path)
        val lines = if (rawText == null) emptyList() else parseLines(rawText)
        OrgDocument(
            date = date,
            lines = lines,
            hash = sha256(canonicalText(lines)),
        )
    }

    override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult {
        val path = dailyPath(date)
        val existingRaw = readText(path)
        val existingLines = existingRaw?.let(::parseLines).orEmpty()
        val existingHash = sha256(canonicalText(existingLines))
        if (existingHash != expectedHash) {
            return SaveResult.Conflict("File changed by another process.")
        }

        val lineSeparator = existingRaw?.let(::detectLineSeparator) ?: "\n"
        val trailingNewline = existingRaw?.endsWith('\n') ?: false
        val outputText = formatOutputText(lines, lineSeparator, trailingNewline)
        return if (writeText(path, outputText)) SaveResult.Success else SaveResult.IoError("Failed to write file")
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
        val data = fileManager.contentsAtPath(path) ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
    }

    private fun writeText(path: String, text: String): Boolean {
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) {
            val empty = NSData.create(bytes = null, length = 0u) ?: return false
            return empty.writeToFile(path, atomically = true)
        }
        val data = bytes.toNSData()
        return data.writeToFile(path, atomically = true)
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

    private fun sha256(text: String): String {
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) return EMPTY_SHA256
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        bytes.usePinned { src ->
            digest.usePinned { dst ->
                CC_SHA256(src.addressOf(0), bytes.size.toUInt(), dst.addressOf(0))
            }
        }
        return digest.joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "${HEX[value ushr 4]}${HEX[value and 0x0f]}"
        }
    }

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
            ?: error("Failed to create NSData")
    }

    private companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val HEX = "0123456789abcdef"
    }
}
