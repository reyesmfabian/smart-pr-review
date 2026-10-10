package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.git.GitException
import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

data class Profile(
    val name: String,
    val description: String,
    /** Globs of repository paths the profile reviews; empty means every file. */
    val include: List<String>,
    /** Review instructions: the Markdown after the frontmatter. */
    val body: String,
    val source: Source,
    /** A repository profile whose working-copy content differs from the pull request base. */
    val local: Boolean,
) {
    enum class Source { REPOSITORY, BUNDLED }
}

data class ProfileContent(val name: String, val description: String, val include: List<String>, val body: String)

// Editors on Windows may save a byte order mark.
private val FRONTMATTER = Regex("""^﻿?---\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n([\s\S]*))?\z""")

/** Reads "---\n<yaml>\n---\n<body>" (BOM tolerated). Null when there is no frontmatter or no name. */
fun parseProfile(text: String): ProfileContent? {
    val match = FRONTMATTER.find(text) ?: return null
    val data = try {
        Yaml().load<Any?>(match.groupValues[1])
    } catch (e: Exception) {
        return null
    } as? Map<*, *> ?: return null
    val name = (data["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val include = when (val i = data["include"]) {
        is String -> listOf(i)
        is List<*> -> i.filterIsInstance<String>()
        else -> emptyList()
    }
    return ProfileContent(name, (data["description"] as? String)?.trim() ?: "", include, match.groupValues[2].trim())
}

/** minimatch semantics with dot:true: `**` matches zero or more segments, `*`/`?` never cross `/`, `{a,b}` alternation. */
fun globToRegex(glob: String): Regex {
    val out = StringBuilder()
    var braces = 0
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            glob.startsWith("**/", i) -> { out.append("(?:[^/]+/)*"); i += 2 }
            glob.startsWith("**", i) -> { out.append(".*"); i++ }
            c == '*' -> out.append("[^/]*")
            c == '?' -> out.append("[^/]")
            c == '{' -> { braces++; out.append("(?:") }
            c == '}' && braces > 0 -> { braces--; out.append(')') }
            c == ',' && braces > 0 -> out.append('|')
            else -> out.append(Regex.escape(c.toString()))
        }
        i++
    }
    // An unclosed brace is literal text in minimatch; close it so the pattern still compiles.
    repeat(braces) { out.append(')') }
    return Regex(out.toString())
}

fun appliesTo(include: List<String>, path: String): Boolean = include.isEmpty() || include.any { globToRegex(it).matches(path) }

data class ProfileSources(
    val git: Git,
    /** Repository-relative folder, from the setting profilesPath. */
    val folder: String,
    /** Commit the pull request starts from. */
    val baseSha: String,
    /** Files the pull request changes. */
    val changed: List<ChangedFile>,
    /** Profiles that ship with the plugin: file name to text. */
    val bundled: () -> Map<String, String>,
)

/**
 * Repository profiles come from the working copy, so local edits apply at once, except the ones the pull
 * request touches: those it adds are ignored and those it modifies, renames or deletes are read at the
 * base, so a pull request cannot change the profile that reviews it. Bundled profiles fill in the names
 * the repository does not define. Blocking: never call on the EDT.
 */
fun loadProfiles(s: ProfileSources): List<Profile> {
    val repository = repositoryProfiles(s)
    val names = repository.map { it.name }.toSet()
    val extra = s.bundled().entries.sortedBy { it.key }.mapNotNull { parseProfile(it.value) }
        .filter { it.name !in names }
        .map { Profile(it.name, it.description, it.include, it.body, Profile.Source.BUNDLED, local = false) }
    return repository + extra
}

private fun repositoryProfiles(s: ProfileSources): List<Profile> {
    val root = s.git.root
    val absolute = root.resolve(s.folder).normalize()
    // The setting can come from the repository's own settings: never read outside the repository.
    if (!absolute.startsWith(root.normalize())) return emptyList()
    // One spelling for the folder, as git writes paths, so the pull request's changes are recognized.
    val inside = root.normalize().relativize(absolute).toString().replace('\\', '/')
    val prefix = if (inside.isEmpty()) "" else "$inside/"
    // A folder that resolves somewhere else (a symlink the pull request may have added) is read at the base only.
    val real = try { absolute.toRealPath() } catch (e: IOException) { null }
    val expected = root.toRealPath().resolve(inside)
    val working = if (real == expected) markdownFiles(absolute).mapKeys { "$prefix${it.key}" } else emptyMap()
    val listing = try {
        s.git.run("ls-tree", "-z", "--name-only", s.baseSha, "--", prefix.ifEmpty { "." })
    } catch (e: GitException) {
        ""
    }
    val atBase = listing.split('\u0000').filter { it.endsWith(".md") && '/' !in it.removePrefix(prefix) }.toSet()
    val added = s.changed.filter { it.status == ChangedFile.Status.A || it.status == ChangedFile.Status.R }.map { it.path }.toSet()
    val changedByPr = s.changed.filter { it.status != ChangedFile.Status.A }.map { it.oldPath ?: it.path }.toSet()

    return (working.keys + atBase).toSortedSet().mapNotNull { path ->
        if (path in added && path !in atBase) return@mapNotNull null
        val base = if (path in atBase) s.git.showFile(s.baseSha, path) else null
        val workingFile = working[path]
        val text = if (path in changedByPr || workingFile == null) base else {
            // Lossy decoding like Node's utf8; an unreadable file is skipped, it must not break the other profiles.
            try { Files.readAllBytes(workingFile).toString(Charsets.UTF_8) } catch (e: IOException) { return@mapNotNull null }
        }
        val parsed = text?.let(::parseProfile) ?: return@mapNotNull null
        Profile(parsed.name, parsed.description, parsed.include, parsed.body, Profile.Source.REPOSITORY, local = text != base)
    }
}

private fun markdownFiles(dir: Path): Map<String, Path> = try {
    Files.list(dir).use { files -> files.filter { it.isRegularFile() && it.fileName.toString().endsWith(".md") }.toList().associateBy { it.fileName.toString() } }
} catch (e: IOException) {
    emptyMap()
}

/** The profiles in the plugin jar (`profiles/index.txt` lists them: jars cannot list directories). File name to text. */
fun bundledProfiles(): Map<String, String> {
    val loader = Profile::class.java
    val index = loader.getResourceAsStream("/profiles/index.txt")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return emptyMap()
    return index.lines().filter { it.isNotBlank() }.associateWith { name ->
        loader.getResourceAsStream("/profiles/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
