package com.example.orgclock.template

import com.example.orgclock.data.SafOrgRepository

class TemplateSyncService(
    private val repository: SafOrgRepository,
    private val templateFileUriProvider: () -> String? = { null },
) {
    suspend fun syncFromFile(fileId: String): Result<Boolean> {
        val source = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        val extractedSections = extractTaggedSections(source.lines)
        if (extractedSections.isEmpty()) {
            return Result.success(false)
        }

        val templateFileUri = templateFileUriProvider()
        val existing = repository.loadTemplate(templateFileUri).getOrElse { return Result.failure(it) }
        val merged = mergeTemplate(existing.lines, extractedSections)
        if (merged == existing.lines) {
            return Result.success(false)
        }

        return when (val save = repository.saveTemplate(merged, existing.hash, templateFileUri)) {
            is com.example.orgclock.data.SaveResult.Success -> Result.success(true)
            is com.example.orgclock.data.SaveResult.Conflict -> Result.failure(IllegalStateException(save.reason))
            is com.example.orgclock.data.SaveResult.ValidationError -> Result.failure(IllegalStateException(save.reason))
            is com.example.orgclock.data.SaveResult.IoError -> Result.failure(IllegalStateException(save.reason))
        }
    }

    private fun extractTaggedSections(lines: List<String>): List<TemplateSection> {
        val headings = parseHeadings(lines)
        val taggedIndices = headings.indices.filter { index ->
            val heading = headings[index]
            heading.hasTplTag && headings.none { candidate ->
                candidate.index != heading.index &&
                    candidate.hasTplTag &&
                    candidate.level < heading.level &&
                    candidate.index < heading.index &&
                    candidate.endExclusive >= heading.endExclusive
            }
        }

        return taggedIndices.map { index ->
            val heading = headings[index]
            TemplateSection(
                pathKey = heading.pathKey,
                lines = lines.subList(heading.index, heading.endExclusive).trimTrailingBlankLines(),
            )
        }
    }

    private fun mergeTemplate(existingLines: List<String>, extractedSections: List<TemplateSection>): List<String> {
        if (existingLines.isEmpty()) {
            return extractedSections.joinSections()
        }

        val existingSections = extractTaggedSections(existingLines)
        if (existingSections.isEmpty()) {
            return extractedSections.joinSections()
        }

        val extractedByPath = extractedSections.associateBy { it.pathKey }
        val mergedSections = buildList {
            existingSections.forEach { section ->
                add(extractedByPath[section.pathKey] ?: section)
            }
            extractedSections.forEach { section ->
                if (existingSections.none { it.pathKey == section.pathKey }) {
                    add(section)
                }
            }
        }
        return mergedSections.joinSections()
    }

    private fun List<TemplateSection>.joinSections(): List<String> {
        val result = mutableListOf<String>()
        forEachIndexed { index, section ->
            if (index > 0 && result.lastOrNull()?.isNotEmpty() == true) {
                result += ""
            }
            result += section.lines
        }
        return result.trimTrailingBlankLines()
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
            val pathKey = (1..level).mapNotNull { titlesByLevel[it] }.joinToString("/")
            rawHeadings += ParsedHeading(
                index = index,
                level = level,
                rawTitle = rawTitle,
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

    private data class ParsedHeading(
        val index: Int,
        val level: Int,
        val rawTitle: String,
        val pathKey: String,
        val endExclusive: Int = index + 1,
    ) {
        val hasTplTag: Boolean
            get() = rawTitle.contains(":TPL:")
    }

    private data class TemplateSection(
        val pathKey: String,
        val lines: List<String>,
    )
}
