# Changelog

## 0.2.0 — 2026-11-18

### Added
- Review profiles accept `exclude` globs: the files they match are not sent to the engine. The bundled profiles exclude minified files, source maps and test snapshots (and lockfiles, except *Security*), and **Create Review Profile** writes an `exclude` example.
- Claude Code, Codex and Cursor ask for the model on every review (the last one first; Cursor lists the models of your account). The model picked is named next to the engine in progress, the summary and comment labels. Engines you add get the same picker with `modelArg`, `models`, `listModelsArgs` and `defaultModel`.

### Changed
- The bundled *General* and *Performance* profiles no longer review lockfiles; *Security* still does, since a lockfile change can point a dependency at another registry or package. Profiles in your repository are unaffected.
- Codex reviews run with medium reasoning effort instead of inheriting the effort of your Codex configuration.
- Cursor no longer has `--model auto` fixed in its arguments: *auto* is the first model offered instead.
- If you redefine `claude`, `codex` or `cursor` in your engine settings, your definition is used as is: no model picker and no fixed reasoning effort unless you add them.
- Update `minimatch` to 11 and the development tools to resolve npm audit advisories. Profile globs match as before.

### Fixed
- Stop the engines of an AI review when another pull request is opened, the open one is refreshed or VS Code closes; they used to keep running to an answer that was then discarded.

## 0.1.1 — 2026-10-25

### Fixed
- Respect `smartPrReview.ai.labelComments` in the review summary: with it off, an AI review no longer adds the "### profile (engine)" heading to its part of the summary.

### Changed
- Update development dependencies to resolve npm audit advisories; none affect the published extension.

## 0.1.0 — 2026-10-10

### Added
- Create, open and manage persistent pull request review worktrees without changing the original checkout.
- Offer worktree cleanup on Exit Review; refuse removal when local files, changes or additional commit history would be lost.

### Fixed
- Harden CLI AI review worktree cleanup: reject symlink traversal, remove untracked and filesystem-equivalent agent configuration, and fail closed when sanitizing fails.
- Remove temporary AI worktrees even when sanitizing fails, and report sandbox warnings in the AI log.

### Changed
- Move the VS Code extension into `vscode/`, with an independent version and changelog.
- Copy bundled profiles and the MIT license from the shared repository assets during builds.
- Update CI and Marketplace release workflows for the monorepo layout.

## 0.0.1

- GitHub pull request review: list, search by number, title or author, open in browser, comments on selected text or line ranges, diffs, viewed files, existing threads, draft comments, code suggestions, publish with vote, checkout.
- Azure DevOps Services and Server 2020+: same workflow, Microsoft sign-in or personal access tokens, Azure DevOps votes, team reviews in *Assigned to me*, fork pull requests, viewed files kept locally.
- AI review with review profiles (bundled General, Security and Performance, or your repository's) and the engine you pick: GitHub Copilot (any of its models), Claude Code, Codex, Cursor or your own CLI. The AI writes in the pull request's language or the one you set (`smartPrReview.ai.language`). Command-line engines run read-only in a temporary worktree without the pull request's hooks or agent configuration.
- English and Spanish UI.
