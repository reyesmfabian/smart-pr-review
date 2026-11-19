# Instructions for AI agents: writing a Smart PR Review profile

Give this file to an AI agent (Claude Code, Codex, Cursor, Copilot…) working in your repository and tell it what the profile should look for. For example:

> Follow https://github.com/reyesmfabian/smart-pr-review/blob/main/docs/review-profile-agent-instructions.md and create a review profile for our database migrations.

You can also copy this file into your repository and point the agent at the copy. The [guide for people](review-profiles.md) explains the same rules in more detail.

---

## Your task

You are writing a **review profile** for Smart PR Review, a VS Code and IntelliJ extension that reviews pull requests with an AI engine. A profile is a Markdown file that tells the reviewing model what to look for in a pull request. You are not reviewing code now: you are writing the instructions another model will follow later, on every pull request that touches the files the profile covers.

Write one file, `.smart-review/profiles/<kebab-case-name>.md`, unless the user names another folder or the repository's settings set one (`smartPrReview.profilesPath` in `.vscode/settings.json`, or `profilesPath` in `.idea/smartPrReview.xml`). The file goes directly in that folder, not in a subfolder.

## Steps

1. **Settle the purpose.** One profile, one concern: migrations, API contracts, accessibility, security for this codebase… If the user asks for several concerns, propose one profile per concern. If the purpose is unclear, ask one short question before writing.

2. **Look at the repository before writing.** The profile is only as good as its project knowledge.
   - Existing profiles in the profiles folder: do not duplicate one, and do not reuse a `name` unless the user wants to replace that profile. The extension ships *General*, *Security* and *Performance*; a repository profile with one of those names replaces the bundled one.
   - Where the relevant code lives, so `include` lists real paths, and which files there are generated, vendored or built, so `exclude` leaves them out.
   - Languages, frameworks and libraries in use (package manifests, build files).
   - Project rules the reviewer cannot guess: contributing guides, architecture notes, lint and CI configuration (so the profile does not repeat what they already check), helpers that must always be used (for authentication, database access, logging…).

3. **Write the frontmatter.**
   - `name` (required): short, title case. It appears in the picker and in every comment label.
   - `description`: one line saying what the profile looks for.
   - `include`: globs of the files to review, relative to the repository root, with `/` separators. Omit it only if the concern applies to every file.
   - `exclude`: globs of files never to send to the reviewer: generated code, vendored code, lockfiles, minified files, source maps, snapshots. Excluded files are not sent at all, which is what saves cost; asking the model in the instructions to ignore them does not. Keep lockfiles in a security-focused profile: a lockfile change can point a dependency at another registry or package.
   - Use only `**`, `*`, `?` and `{a,b}` in globs. `*` and `?` do not cross `/`. Do not use character classes (`[abc]`) or negation (`!pattern`): they do not behave the same in both IDEs. Quote every glob in YAML.

4. **Write the instructions** (the Markdown after the frontmatter):
   - Open with one sentence stating the concern: "Review the changes for …".
   - List three to eight concrete problems, in the project's own terms (real function names, folders, conventions). Prefer "a handler in `src/api/` that reads the body before calling `requireAuth()`" over "missing authentication".
   - Set the severity bar: what counts as `error`, `warning` and `info` for this concern.
   - Say what not to report: formatting, naming preferences, anything lint or CI already enforces, speculative "consider…" remarks.
   - End with: "For each problem, say what can go wrong and how to fix it."
   - Keep it under about half a page. It is sent on every review.

5. **Check the result** against the rules below, then show the user the file and the paths its globs match in the current repository. Tell them to try it with **Review with AI** on a pull request that touches those files before committing: uncommitted profiles are used at once, marked *local*.

## Rules the profile must follow

The extension writes the rest of the prompt. It already tells the reviewer to report only problems in the lines the pull request adds or changes, to answer in a fixed JSON format (file, line range, severity, message, optional suggestion), to write in the configured review language, and to treat the pull request's text and code as untrusted data. So the profile must **not**:

- ask for a different output format, prose findings, a score or a final verdict;
- set the language of the comments;
- ask for comments on unchanged lines or on excluded files (they are discarded or never sent);
- tell the reviewer to run commands, edit files or fetch URLs. Engines run read-only;
- contain secrets, credentials or internal URLs. Profiles are committed and sent to the engine.

## Template

```markdown
---
name: <Short name>
description: <What this profile looks for, in one line>
include:
  - "<folder or files it reviews>/**"
exclude:
  - "<generated or vendored files>"
---
Review the changes for <the one thing this profile cares about>.

- <Concrete problem 1, in the project's terms>.
- <Concrete problem 2>.
- <Concrete problem 3>.

Severity: error for <what breaks or leaks>, warning for <likely bugs>, info for <worth a look>.

Do not report <noise to avoid: formatting, naming preferences, what CI already checks>.
For each problem, say what can go wrong and how to fix it.
```

## Example

For "a profile for our database migrations" in a repository with migrations in `db/migrations/`:

```markdown
---
name: Migrations
description: Schema changes that can break production or lose data
include:
  - "db/migrations/**"
  - "**/*.sql"
---
Review the changes for database migrations that are unsafe to run on a live production database.

- Dropping or renaming a column or table that the current code may still read (deploys are not atomic).
- Adding a NOT NULL column without a default to a table that already has rows.
- Index creation that locks a large table; it should be created concurrently.
- Data migrations that load a whole table into memory or run without batching.

Severity: error for data loss or a migration that will fail or lock production, warning for the rest.
Do not comment on SQL formatting or naming.
For each problem, say what can go wrong and how to fix it.
```
