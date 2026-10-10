package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.git.ChangedFile
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.PathManager
import com.fabianreyes.smartprreview.SmartPrReviewBundle
import com.intellij.openapi.util.text.StringUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

const val DEFAULT_PROFILES_FOLDER = ".smart-review/profiles"

/** Where the review sandboxes are made. */
fun worktreesDir(): Path = PathManager.getSystemDir().resolve("smart-pr-review").resolve("worktrees")

/**
 * A project setting the repository can set (it lives in .idea/smartPrReview.xml). A pull request that edits that file could use it to drop the
 * team's profiles or steer the AI, so then only the [default] counts.
 */
fun repositorySafe(projectValue: String?, changed: List<ChangedFile>, default: String): String {
    // Any case, and the filesystem's own folding (ſ is s): on macOS and Windows, .IDEA/ſmartPrReview.XML is the same file.
    // Also under a subfolder: the project may be opened below the repository root (a monorepo). Over-matching is safe.
    fun isSettings(path: String?) = path?.uppercase(Locale.ROOT)?.lowercase(Locale.ROOT).let { it == ".idea/smartprreview.xml" || it?.endsWith("/.idea/smartprreview.xml") == true }
    val edits = changed.any { isSettings(it.path) || isSettings(it.oldPath) }
    return if (edits) default else projectValue ?: default
}

private val PLAIN_LANGUAGE = Regex("^[\\p{L} ()-]{1,40}$")

/** The language the AI writes in: the setting when it is a plain language name (it goes into the prompt), else the pull request's, falling back to the IDE's. */
fun aiLanguage(setting: String?, displayLanguage: Locale): String {
    val configured = setting?.trim().orEmpty()
    if (PLAIN_LANGUAGE.matches(configured)) return configured
    val fallback = displayLanguage.getDisplayLanguage(Locale.ENGLISH).ifEmpty { displayLanguage.toLanguageTag() }
    return "the language of the pull request title and description ($fallback if that is unclear)"
}

/** The setting ai timeout in milliseconds, between 1 minute and 1 day. */
fun aiTimeoutMs(minutes: Int): Long = minutes.coerceIn(1, 1440) * 60_000L

private val INSTALL_GUIDES = mapOf(
    "copilot" to "https://docs.github.com/copilot/how-tos/copilot-cli/cli-getting-started",
    "claude" to "https://code.claude.com/docs/en/setup",
    "codex" to "https://github.com/openai/codex",
    "cursor" to "https://cursor.com/docs/cli/installation",
)

/** Where to install a bundled engine; null for the user's own engines. */
fun installGuide(id: String): String? = INSTALL_GUIDES[id]

/** "A, B: quota; C: bad JSON": profiles that failed the same way are listed once. */
fun describeFailures(failures: List<AiReviewResult.Failure>): String =
    failures.groupBy({ it.error }, { it.profile }).entries.joinToString("; ") { (error, profiles) -> "${profiles.joinToString(", ")}: $error" }

private val gson = GsonBuilder().disableHtmlEscaping().create()

/** The file "Create Review Profile" writes; the name is a JSON string, which is also a valid YAML one. */
fun profileTemplate(name: String): String = """---
name: ${gson.toJson(name)}
description: What this profile looks for
include:
  - "**/*"
---
Review the changes for <what this profile cares about>. For each problem, say what can go wrong and how to fix it.

- <First thing to check>
- <Second thing to check>
"""

/** Notification text is HTML: the profile names and engine errors in it come from the repository and the engine, so they are escaped. */
fun addedWithFailuresText(drafts: Int, failures: List<AiReviewResult.Failure>): String =
    SmartPrReviewBundle.message("ai.addedWithFailures", drafts, failures.size, StringUtil.escapeXmlEntities(describeFailures(failures)))

fun failedText(error: Throwable): String =
    SmartPrReviewBundle.message("ai.failed", StringUtil.escapeXmlEntities(error.message ?: error.javaClass.simpleName))

/** Whether [folder] is inside [root], also through symlinks: the deepest existing ancestor of the folder must resolve inside the real root. */
fun isInsideRepository(root: Path, folder: Path): Boolean {
    val lexical = root.toAbsolutePath().normalize()
    val target = folder.toAbsolutePath().normalize()
    if (!target.startsWith(lexical)) return false
    var existing: Path? = target
    while (existing != null && !Files.exists(existing)) existing = existing.parent
    return existing != null && existing.toRealPath().startsWith(lexical.toRealPath())
}
