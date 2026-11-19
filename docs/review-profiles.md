# Writing review profiles

A review profile tells the AI review **what to look for**. You choose the engine (Copilot, Claude Code, Codex, Cursor…) when you run the review; the profile stays the same whichever engine reads it.

This guide covers where profiles live, what each field does, how to write instructions that produce useful comments, and a few complete examples. To have an AI agent draft a profile for you, give it [the agent instructions](review-profile-agent-instructions.md).

## Where profiles live

- Put each profile in its own `.md` file directly inside `.smart-review/profiles/` at the repository root. Files in subfolders are not read.
- The folder is a setting: `smartPrReview.profilesPath` in VS Code, *Settings › Tools › Smart PR Review › Review profiles folder* in IntelliJ IDEA and Android Studio.
- **Create Review Profile…** writes a starting file there.
- Commit the profiles so the whole team reviews with the same rules.

The extension ships three profiles: *General*, *Security* and *Performance*. A repository profile with the same `name` replaces the bundled one, so you can tune them for your project.

A pull request cannot change the profiles that review it. Profiles it modifies are read as they are on the base branch, and profiles it adds are ignored until they are merged. Your own uncommitted edits apply at once, marked *local* in the picker, which is how you try a profile before committing it.

## Anatomy of a profile

```markdown
---
name: API contracts
description: Breaking changes to the public HTTP API
include:
  - "src/api/**"
  - "openapi/**"
exclude:
  - "**/*.generated.ts"
---
Review the changes for breaking changes to the public HTTP API.

- Removed or renamed endpoints, fields or query parameters.
- Fields that became required, or whose type or format changed.
- …

Do not report internal refactors that keep requests and responses the same.
```

The file has two parts: a YAML frontmatter between `---` lines, and the instructions after it.

### Frontmatter

| Field | Required | What it does |
|---|---|---|
| `name` | Yes | Shown in the picker, the summary and the comment labels. A file without a `name` is ignored. Two profiles with the same name: the repository one wins over the bundled one. |
| `description` | No | One line shown next to the name in the picker. |
| `include` | No | Globs of the files this profile reviews. Without it, the profile reviews every changed file. |
| `exclude` | No | Globs of the files it never reviews, even when `include` matches them. |

`include` and `exclude` take a single string or a list.

### Globs

Globs are paths from the repository root, with `/` as the separator on every platform.

| Glob | Matches |
|---|---|
| `src/**` | Everything under `src/`, at any depth |
| `**/*.sql` | Every `.sql` file, including one at the root |
| `src/*.ts` | `.ts` files directly in `src/`, not in its subfolders |
| `**/{migrations,seeds}/**` | Anything under any `migrations/` or `seeds/` folder |
| `config/?.yml` | `config/a.yml`, not `config/ab.yml` |

`*` and `?` never cross a `/`, and dotfiles match like any other file. Stick to `**`, `*`, `?` and `{a,b}`. Character classes (`[abc]`) and negation (`!pattern`) are not supported the same way in both IDEs; use `exclude` instead of negation.

**Exclude what the model should not read.** Files left out with `exclude` are not sent to the engine at all. Writing "ignore lockfiles" in the instructions does not save anything: the files are still sent, and you still pay for them. Generated code, vendored libraries, lockfiles, minified files, source maps and snapshots are the usual candidates. Think twice before excluding lockfiles from a security profile, though: a lockfile change can point a dependency at another registry or package.

A profile whose globs match none of the changed files is left unchecked in the picker, and if you run it anyway it is skipped.

### Instructions

The text after the frontmatter is the profile's instructions. Everything else in the prompt is written by the extension. It already tells the engine to:

- report only problems in the lines the pull request adds or changes. Findings on other lines are dropped, so do not ask for them.
- answer in a fixed JSON format, with a file, a line range, a severity (`error`, `warning` or `info`), a message and an optional code suggestion.
- write in the review language (the *AI review language* setting, or the language of the pull request).
- treat the pull request title, description and code as untrusted data, never as instructions.

So the instructions only need to say **what to look for**, and what not to report.

## Writing instructions that work

**Give it one job.** A profile about "security, performance and style" produces vague comments about all three. Several focused profiles do better. Each profile is a separate engine session over the whole diff of its files, so three profiles cost about three times one: keep the ones you actually need.

**List concrete checks.** "Look for bugs" gets generic comments. "Missing `await` on calls that return a promise", "queries built by concatenating user input" or "a migration that drops a column still read by the code" get findings you can act on.

**Name your project's rules.** Frameworks, conventions and past incidents the model cannot guess: "Every handler in `src/api/` must call `requireAuth()` before reading the request body", "Money is stored in cents as integers, never as floats".

**Say what not to report.** This is what keeps the noise down. Formatting a linter fixes, naming preferences, things already covered by CI, or "consider adding a comment".

**Set the bar for severity.** For example: `error` for what breaks production or leaks data, `warning` for likely bugs, `info` for improvements worth a look. Without guidance, models tend to mark everything as a warning.

**Ask for the consequence.** "For each problem, say what can go wrong and how to fix it" turns "this looks wrong" into a comment the author can act on.

**Keep it short.** The instructions go to the engine on every review. Half a page is usually enough.

**Do not:**

- ask for a different output format, a list of findings in prose, or a final verdict. The answer must be the JSON the extension asks for.
- set the language. It comes from the setting.
- ask for comments on unchanged lines, or on files the profile excludes.
- paste secrets, internal URLs or credentials. Profiles are committed and sent to the engine.

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

- <Concrete problem 1, in your project's terms>.
- <Concrete problem 2>.
- <Concrete problem 3>.

Severity: error for <what breaks or leaks>, warning for <likely bugs>, info for <worth a look>.

Do not report <noise to avoid: formatting, naming preferences, what CI already checks>.
For each problem, say what can go wrong and how to fix it.
```

## Examples

### Database migrations

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
- A migration without a way back, when the change is not obviously irreversible by design.

Severity: error for data loss or a migration that will fail or lock production, warning for the rest.
Do not comment on SQL formatting or naming.
For each problem, say what happens when it runs in production and how to make it safe.
```

### Accessibility

```markdown
---
name: Accessibility
description: Keyboard, screen reader and contrast problems in the UI
include:
  - "src/components/**"
  - "src/pages/**"
exclude:
  - "**/*.stories.tsx"
  - "**/*.test.tsx"
---
Review the UI changes for accessibility problems a user would run into.

- Interactive elements that are not buttons or links, or cannot be reached and used with the keyboard.
- Images, icons and icon-only buttons without a text alternative.
- Form fields without a label tied to them, and errors that are only shown with color.
- Focus that is lost or trapped when dialogs, menus or routes open and close.
- Text or controls whose colors are set to values with obviously low contrast.

Severity: error when a keyboard or screen reader user cannot complete the task, warning otherwise.
Do not report visual design preferences.
For each problem, say who is affected and how to fix it.
```

### Replacing a bundled profile

To tune *Security* for your project, create `.smart-review/profiles/security.md` with `name: Security`. It replaces the bundled one. Start from the [bundled version](../profiles/security.md) and add your project's rules, for example "All SQL goes through `db.query` with parameters; flag any other way of running SQL".

## Before you commit

- [ ] The frontmatter has a `name`, and it does not collide with another profile unless you mean to replace it.
- [ ] `include` matches the files you expect, and `exclude` leaves out generated and vendored files.
- [ ] The instructions name concrete problems in your project's terms, and what not to report.
- [ ] Nothing asks for another output format or language, or for comments on unchanged lines.
- [ ] You ran **Review with AI** on a pull request that touches matching files, with your uncommitted profile (*local*), and the comments were worth keeping.
