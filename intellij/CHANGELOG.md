# Changelog — IntelliJ Platform

## 0.4.0 — 2026-11-19

### Added
- `reasoningEffort` on a URL engine (`none` to `xhigh`) is sent as `reasoning_effort`, so a reasoning model can think less and answer sooner.

### Changed
- A URL engine reviews with one profile at a time. A local server answers one request at a time, so the other profiles waited in its queue and that wait counted against the timeout.

## 0.3.0 — 2026-11-19

### Added
- AI engines reached by URL: an entry of the engines JSON with `url` reviews with any OpenAI-compatible server (Ollama, LM Studio, vLLM, llama.cpp, LiteLLM, OpenRouter), local, on your network or online. It gets the new version of each changed file, split to fit `contextWindow` minus `maxOutputTokens`, needs no worktree, and asks for the model on every review, offering the ones the server lists. *Set API Key of an AI Engine* stores its key per URL in the IDE's PasswordSafe, so it never reaches another server.
- Reasoning models work with URL engines: their reasoning is left out of the answer, and an answer cut short by `maxOutputTokens` fails with a message that says so. A rejected API key, a server that cannot be reached or a URL that is not an OpenAI-compatible API are reported naming the server. Cancelling the review or reaching its timeout stops the request.

## 0.2.0 — 2026-11-18

### Added
- Review profiles accept `exclude` globs: the files they match are not sent to the engine. The bundled profiles exclude minified files, source maps and test snapshots (and lockfiles, except *Security*), and *Create Review Profile* writes an `exclude` example.
- Claude Code, Codex and Cursor ask for the model on every review (the last one first; Cursor lists the models of your account). The model picked is named next to the engine in progress, the summary and comment labels. Engines you add get the same picker with `modelArg`, `models`, `listModelsArgs` and `defaultModel`.

### Changed
- The bundled *General* and *Performance* profiles no longer review lockfiles; *Security* still does, since a lockfile change can point a dependency at another registry or package. Profiles in your repository are unaffected.
- Codex reviews run with medium reasoning effort instead of inheriting the effort of your Codex configuration.
- Cursor no longer has `--model auto` fixed in its arguments: *auto* is the first model offered instead.
- If you redefine `claude`, `codex` or `cursor` in your engines JSON, your definition is used as is: no model picker and no fixed reasoning effort unless you add them.

### Fixed
- Stop the engines of an AI review when another pull request is opened, the open one is refreshed, or the project or the IDE closes; they used to keep running to an answer that was then discarded.

## 0.1.2 — 2026-10-25

### Fixed
- Respect *Label AI-generated comments* in the review summary: with it off, an AI review no longer adds the "### profile (engine)" heading to its part of the summary.

### Build
- Publish to JetBrains Marketplace from stable GitHub releases tagged `intellij-v<version>`.

## 0.1.1 — 2026-10-10

### Fixed
- Preserve JSON arguments when creating and removing review worktrees on Windows.
- Preserve quotes and shell metacharacters when passing CLI arguments to Windows batch shims, including npm shims.
- Compare registered worktree paths using native paths instead of platform-specific separators.
- Run Linux UI interaction tests with a virtual display and retain CI test reports.

## 0.1.0 — 2026-10-10

Initial IntelliJ Platform release for IntelliJ IDEA and Android Studio.

### Added
- GitHub and Azure DevOps Services/Server pull request browsing and search, authentication, conversations, viewed files, drafts, suggestions and review publishing with a summary and decision.
- Native Before/After side-by-side diffs, a dedicated review window and explicit pull request checkout controls.
- Persistent review worktrees: create, open, manage and safely remove them; offer cleanup on Exit Review without deleting local changes or extra commit history.
- CLI AI reviews with Markdown profiles, configurable engines, temporary sanitized worktrees, timeout and cancellation. AI findings remain drafts until publishing.
- English and Spanish interface, a plugin icon, contact metadata, privacy notice and bundled MIT license.

### Fixed
- Show deleted files and removed code in red, additions in green, modifications in blue and renames in purple.
- Wrap comment previews to the editor width instead of forcing horizontal scrolling; use the same vertically scrolling comment editor from the gutter and context menu.
- Preserve collapsed tree groups, comment-editor text, the selected diff and PR revision during asynchronous review operations.

### Build and compatibility
- Independent Gradle build and changelog in `intellij/`; bundled profiles shared with the VS Code extension.
- Compile against IntelliJ Platform 2025.1 (build 251) using JDK 21.
- Plugin Verifier confirmed compatibility with IntelliJ IDEA Community 2025.1.7 and Android Studio AI-262.9437.185.2621.16467767. Future IDE versions are not all verified.
