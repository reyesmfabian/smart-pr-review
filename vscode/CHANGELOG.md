# Changelog

## 0.4.0 — 2026-11-19

### Added
- `reasoningEffort` on a URL engine (`none` to `xhigh`) is sent as `reasoning_effort`, so a reasoning model can think less and answer sooner.

### Fixed
- An AI review that failed or timed out kept **Review with AI** busy until its warning was closed, and the warning could sit unnoticed in the notification center: a new review said one was already running until VS Code restarted. The same happened when the chosen engine was not installed.
- A URL engine cut every request at 5 minutes: without streaming the server sends nothing until the model has finished, and VS Code's `fetch` gives up on a response whose headers take that long. On a large pull request every part of the review failed and no comments came back. Only `smartPrReview.ai.timeoutMinutes` stops the request now.

### Changed
- A URL engine reviews with one profile at a time. A local server answers one request at a time, so the other profiles waited in its queue and that wait counted against the timeout.

## 0.3.0 — 2026-11-19

### Added
- AI engines reached by URL: an entry of `smartPrReview.engines` with `url` reviews with any OpenAI-compatible server (Ollama, LM Studio, vLLM, llama.cpp, LiteLLM, OpenRouter), local, on your network or online. Like Copilot it gets the new version of each file, split to fit `contextWindow` minus `maxOutputTokens`, and asks for the model on every review, offering the ones the server lists. **Set API Key of an AI Engine** stores its key per URL in VS Code's secret storage, so it never reaches another server.
- Reasoning models work with URL engines: their reasoning is left out of the answer, and an answer cut short by `maxOutputTokens` fails with a message that says so. A rejected API key, a server that cannot be reached or a URL that is not an OpenAI-compatible API are reported naming the server.

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
