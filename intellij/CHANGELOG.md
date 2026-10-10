# Changelog — IntelliJ Platform

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
