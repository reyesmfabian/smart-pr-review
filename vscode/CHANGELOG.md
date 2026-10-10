# Changelog

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
