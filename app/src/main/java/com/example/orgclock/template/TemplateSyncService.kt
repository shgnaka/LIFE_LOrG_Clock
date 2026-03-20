package com.example.orgclock.template

import com.example.orgclock.data.FileWriteIntent
import com.example.orgclock.data.SafOrgRepository

class TemplateSyncService(
    private val repository: SafOrgRepository,
    private val templateFileUriProvider: () -> String? = { null },
) {
    suspend fun syncFromFile(fileId: String): Result<Boolean> {
        val source = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        val headings = parseHeadings(source.lines)
        val cleanedSourceLines = removeTplTagsFromLines(source.lines)
        val extractedSections = extractTaggedSections(source.lines, headings)

        var changed = false
        if (cleanedSourceLines != source.lines) {
            when (
                val save = repository.saveFile(
                    fileId = fileId,
                    lines = cleanedSourceLines,
                    expectedHash = source.hash,
                    writeIntent = FileWriteIntent.UserEdit,
                )
            ) {
                is com.example.orgclock.data.SaveResult.Success -> changed = true
                is com.example.orgclock.data.SaveResult.Conflict -> {
                    return Result.failure(IllegalStateException(save.reason))
                }
                is com.example.orgclock.data.SaveResult.ValidationError -> {
                    return Result.failure(IllegalStateException(save.reason))
                }
                is com.example.orgclock.data.SaveResult.IoError -> {
                    return Result.failure(IllegalStateException(save.reason))
                }
                is com.example.orgclock.data.SaveResult.RoundTripMismatch -> {
                    return Result.failure(IllegalStateException(save.reason))
                }
            }
        }

        if (extractedSections.isEmpty()) {
            return Result.success(changed)
        }

        val templateFileUri = templateFileUriProvider()
        val existing = repository.loadTemplate(templateFileUri).getOrElse { return Result.failure(it) }
        val merged = mergeTemplate(existing.lines, extractedSections)
        if (merged == existing.lines) {
            return Result.success(changed)
        }

        return when (val save = repository.saveTemplate(merged, existing.hash, templateFileUri)) {
            is com.example.orgclock.data.SaveResult.Success -> Result.success(true)
            is com.example.orgclock.data.SaveResult.Conflict -> Result.failure(IllegalStateException(save.reason))
            is com.example.orgclock.data.SaveResult.ValidationError -> Result.failure(IllegalStateException(save.reason))
            is com.example.orgclock.data.SaveResult.IoError -> Result.failure(IllegalStateException(save.reason))
            is com.example.orgclock.data.SaveResult.RoundTripMismatch -> Result.failure(IllegalStateException(save.reason))
        }
    }

    private fun removeTplTagsFromLines(lines: List<String>): List<String> {
        return lines.map { line ->
            if (getHeadingLevel(line) == 0 || !line.contains(TPL_TAG)) {
                line
            } else {
                stripTplTag(line)
            }
        }
    }

    private fun extractTaggedSections(lines: List<String>, headings: List<ParsedHeading>): List<TemplateSection> {
        return headings.filter { it.hasTplTag }.map { heading ->
            TemplateSection(
                hierarchy = heading.hierarchy,
                pathKey = heading.pathKey,
                lines = lines.subList(heading.index, heading.endExclusive)
                    .map(::stripTplTag)
                    .trimTrailingBlankLines(),
            )
        }
    }

    private fun mergeTemplate(existingLines: List<String>, extractedSections: List<TemplateSection>): List<String> {
        if (existingLines.isEmpty()) {
            return extractedSections.joinToString("\n") { it.lines.joinToString("\n") }
                .split('\n')
                .trimTrailingBlankLines()
        }

        val updated = existingLines.toMutableList()
        extractedSections.forEach { section ->
            if (containsPath(updated, section.pathKey)) {
                return@forEach
            }
            insertSection(updated, section)
        }
        return updated.trimTrailingBlankLines()
    }

    private fun insertSection(updated: MutableList<String>, section: TemplateSection) {
        val headings = parseHeadings(updated)
        val insertIndex = findInsertIndex(headings, updated.size, section.hierarchy)
        updated.addAll(insertIndex, section.lines)
    }

    private fun findInsertIndex(
        headings: List<ParsedHeading>,
        lineCount: Int,
        hierarchy: List<String>,
    ): Int {
        if (hierarchy.isEmpty()) {
            return lineCount
        }

        val parentPath = hierarchy.joinToString("/")
        val parent = headings.firstOrNull { it.pathKey == parentPath }
        return parent?.endExclusive ?: lineCount
    }

    private fun containsPath(lines: List<String>, pathKey: String): Boolean {
        return parseHeadings(lines).any { it.pathKey == pathKey }
    }

    private fun List<String>.trimTrailingBlankLines(): List<String> {
        var end = size
        while (end > 0 && this[end - 1].isBlank()) {
            end -= 1
        }
        return subList(0, end)
    }

    private fun parseHeadings(lines: List<String>): List<ParsedHeading> {
        val regex = Regex("""^(\*+)\s+(.*)$""")
        val rawHeadings = mutableListOf<ParsedHeading>()
        val titlesByLevel = mutableMapOf<Int, String>()

        lines.forEachIndexed { index, line ->
            val match = regex.matchEntire(line) ?: return@forEachIndexed
            val level = match.groupValues[1].length
            val rawTitle = match.groupValues[2].trim()
            val title = normalizeHeadingTitle(rawTitle)
            titlesByLevel[level] = title
            titlesByLevel.keys.removeAll { it > level }
            val hierarchy = (1 until level).mapNotNull { titlesByLevel[it] }
            val pathKey = (hierarchy + title).joinToString("/")
            rawHeadings += ParsedHeading(
                index = index,
                level = level,
                rawTitle = rawTitle,
                hierarchy = hierarchy,
                pathKey = pathKey,
            )
        }

        return rawHeadings.mapIndexed { index, heading ->
            val endExclusive = rawHeadings
                .drop(index + 1)
                .firstOrNull { candidate -> candidate.level <= heading.level }
                ?.index
                ?: lines.size
            heading.copy(endExclusive = endExclusive)
        }
    }

    private fun normalizeHeadingTitle(raw: String): String {
        val trimmed = raw.trim()
        val tagMatch = Regex("^(.*?)(\\s+:[A-Za-z0-9_@#%:]+:)$").find(trimmed)
        return tagMatch?.groupValues?.get(1)?.trim()?.ifEmpty { trimmed } ?: trimmed
    }

    private fun getHeadingLevel(line: String): Int {
        val match = Regex("""^(\*+)\s+""").find(line) ?: return 0
        return match.groupValues[1].length
    }

    private fun stripTplTag(line: String): String {
        if (!line.contains(TPL_TAG)) return line
        val cleaned = line.replace("""(?<=\s):TPL:""".toRegex(), "")
            .replace(Regex("\\s{2,}"), " ")
            .trimEnd()
        return cleaned
    }

    private data class ParsedHeading(
        val index: Int,
        val level: Int,
        val rawTitle: String,
        val hierarchy: List<String>,
        val pathKey: String,
        val endExclusive: Int = index + 1,
    ) {
        val hasTplTag: Boolean
            get() = rawTitle.contains(TPL_TAG)
    }

    private data class TemplateSection(
        val hierarchy: List<String>,
        val pathKey: String,
        val lines: List<String>,
    )

    private companion object {
        const val TPL_TAG = ":TPL:"
    }
}
