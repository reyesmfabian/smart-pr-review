package com.fabianreyes.smartprreview.git

data class ChangedFile(
    val status: Status,
    val path: String,
    /** Previous path of a renamed file. */
    val oldPath: String? = null,
) {
    enum class Status { A, M, D, R }
}

/** 1-based, inclusive. */
data class LineRange(val start: Int, val end: Int)

/** Parses `git diff --name-status -z -M`: renames carry two paths, copies count as additions. */
fun parseNameStatus(output: String): List<ChangedFile> {
    val tokens = output.split('\u0000')
    val files = mutableListOf<ChangedFile>()
    var i = 0
    while (i < tokens.size && tokens[i].isNotEmpty()) {
        when (val code = tokens[i++][0]) {
            'R' -> {
                files += ChangedFile(ChangedFile.Status.R, path = tokens[i + 1], oldPath = tokens[i])
                i += 2
            }
            'C' -> {
                files += ChangedFile(ChangedFile.Status.A, path = tokens[i + 1])
                i += 2
            }
            else -> {
                val status = when (code) {
                    'A' -> ChangedFile.Status.A
                    'D' -> ChangedFile.Status.D
                    else -> ChangedFile.Status.M
                }
                files += ChangedFile(status, tokens[i++])
            }
        }
    }
    return files
}

private val HUNK = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""", RegexOption.MULTILINE)

/** Start and length on the head side of each hunk header. */
private fun hunks(diff: String): List<Pair<Int, Int>> =
    HUNK.findAll(diff).map { m -> m.groupValues[1].toInt() to (m.groups[2]?.value?.toInt() ?: 1) }.toList()

fun parseHunkRanges(diff: String): List<LineRange> =
    hunks(diff).mapNotNull { (start, count) -> if (count > 0) LineRange(start, start + count - 1) else null }

/**
 * The lines a -U0 diff adds or changes. "+22,0" removes lines after line 22: a comment about the removed
 * code goes on line 22 or 23, so both count.
 */
fun parseChangedRanges(diff: String): List<LineRange> =
    hunks(diff).map { (start, count) -> if (count > 0) LineRange(start, start + count - 1) else LineRange(maxOf(start, 1), start + 1) }
