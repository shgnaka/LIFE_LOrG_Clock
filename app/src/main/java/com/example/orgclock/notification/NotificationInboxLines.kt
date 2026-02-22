package com.example.orgclock.notification

internal fun <T> buildInboxLines(
    entries: List<T>,
    maxLines: Int,
    entryLineBuilder: (T) -> String,
    moreLineBuilder: (Int) -> String,
): List<String> {
    val safeMaxLines = maxOf(1, maxLines)
    val lines = entries.take(safeMaxLines).map(entryLineBuilder).toMutableList()
    if (entries.size > safeMaxLines) {
        lines += moreLineBuilder(entries.size - safeMaxLines)
    }
    return lines
}

