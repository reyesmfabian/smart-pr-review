# Smart PR Review

Review pull requests without leaving VS Code: browse the changes, comment on the changed code, suggest code, and publish your review with a vote.

> **Status:** GitHub, Azure DevOps Services and Azure DevOps Server, with AI reviews by GitHub Copilot, Claude Code, Codex, Cursor or any CLI you configure.

## Features

- **GitHub and Azure DevOps** (Services and Server 2020 or later) with the same workflow.
- **Pull request list** grouped into *Assigned to me* (including reviews requested from your Azure DevOps teams), *Created by me* and *All open*. The 🔍 button searches the open pull requests by number, title or author, and opens any number you type, even a closed pull request.
- **Side-by-side diffs** for every changed file, including renames and deletions, listed in their folders. Mark files as viewed; the state syncs with GitHub (Azure DevOps has no API for it, so it stays on your machine).
- **Existing conversations** appear in the editor gutter, labeled *Active* or *Resolved*. Reply, resolve and reopen them. A conversation whose lines changed since it was written (GitHub marks it outdated; Azure DevOps could not track it to the latest iteration) is shown on the version of the file it was written against, labeled *Outdated*. *Current Pull Request* lists them under *Active comments* and *Resolved comments* (collapsed); click one to go to its line. In VS Code's Comments panel, **Filter → Show Resolved** hides the resolved ones.
- **Draft comments and code suggestions.** On GitHub you can comment on the lines of the diff; on Azure DevOps, on any line of a changed file. Select the text (part of a line, or several lines) before clicking **+** to comment on exactly that: the draft marks the selection in the editor, and is published on its lines (GitHub anchors comments to whole lines; Azure DevOps keeps the exact span). **Add as Code Suggestion** (next to **Add Draft Comment**) turns the comment into a suggestion prefilled with those lines, ready to edit; **Add Code Suggestion** in the editor's context menu does the same for the selection. *Current Pull Request* lists them under *Drafts*, as they are written; click one to go to its line. Nothing is posted until you press **Publish Review**, which sends every draft together with your summary and vote (GitHub: comment, approve, request changes — GitHub requires a summary to comment or request changes; Azure DevOps: comment without a vote, approve, approve with suggestions, wait for author, reject). On Azure DevOps, suggestions appear as code blocks.
- **Review window** (⧉ in *Current Pull Request*): the pull request in a window of its own, with the diffs beside it. Its files in their folders with viewed checkboxes and the review progress, the drafts (by AI profile and severity), the active and resolved comments, and how many are left in each file; click one to go to its line. An AI review in progress shows there too, and can be cancelled.
- **Open in Browser** (🔗 on each pull request of the list, and in *Current Pull Request*) opens the pull request on GitHub or Azure DevOps.
- **Checkout** the pull request branch to get full code navigation; your drafts follow you. The local branch has the pull request branch's name and tracks it on the remote, so `git pull` and `git push` work on it; a fork's pull request is checked out as `pr/<n>` instead. Drafts point at lines of the pull request version, so local edits after checkout do not move them.
- **Forks:** GitHub fork pull requests are fetched from the base repository; Azure DevOps fork pull requests from the fork itself (so they open even with merge conflicts).
- English and Spanish UI.

## Review worktrees (VS Code and IntelliJ)

**Create Review Worktree** creates a sibling folder at the PR's exact commit, without switching your current branch or touching local edits. **Open in New Window** opens that folder in the IDE. It uses detached HEAD: create a branch there if you want to keep new commits. Git hooks and automatic Git LFS downloads are skipped during creation; this is a normal working copy, **not** the AI engine's sanitized sandbox.

**Review Worktrees…** lists these folders and offers Open or Remove. The registry is local to the Git repository and shared by both plugins; it survives IDE restarts. **Exit Review** also offers to manage the current PR's worktrees. Closing the window or publishing a review never silently deletes them.

Before removal, save and close every editor using the worktree. Removal refuses local changes, untracked **and ignored** files (including generated `.idea/` or dependency folders), new commits in HEAD or its reflog, and the worktree running the command. There is no force-delete button. Preserve any work first; worktrees with additional commit history require manual Git cleanup. Unrelated worktrees and the original checkout are never removed by this flow.

## AI review

**Review with AI** (the ✨ button of *Current Pull Request*) reviews the open pull request with one or more **review profiles** and the **engine** you pick, then turns each finding into a draft comment on its line. Only the lines the pull request adds or changes get comments: findings on other lines are dropped. Nothing is published until you review the drafts and press **Publish Review**.

**Review profiles** are Markdown files with a frontmatter:

```markdown
---
name: Security
description: Injection, secrets, input validation
include:
  - "src/**"
---
Review the changes for security problems. For each one, say how it could be exploited.
```

- Put them in `.smart-review/profiles/` (setting `smartPrReview.profilesPath`) and commit them, so the whole team reviews with the same rules. **Create Review Profile** writes a template; use the frontmatter and instructions shown above as a starting point.
- *General*, *Security* and *Performance* ship with the extension; a repository profile with the same name replaces the bundled one.
- A pull request cannot change the profiles that review it: profiles it modifies are read from the base, and profiles it adds are ignored. Your uncommitted edits apply at once (marked *local*).

**Engines** — the profile says *what* to review; you choose *who* reviews when you run it (`smartPrReview.defaultEngine` is preselected):

| Engine | How it runs | Needs |
|---|---|---|
| **GitHub Copilot** (default) | VS Code language models (`vscode.lm`); gets the diff and the new version of each file, split into parts that fit the model | GitHub Copilot signed in |
| **Claude Code** | `claude -p --restricted --strict-mcp-config` (no project hooks, settings or MCP servers) with only the Read, Grep and Glob tools | `claude` in PATH, signed in |
| **Codex** | `codex exec` in a read-only sandbox, approvals disabled | `codex` in PATH, signed in |
| **Cursor** | `cursor-agent -p` in ask mode, sandboxed, model *auto* | `cursor-agent` in PATH, signed in |

With several Copilot models available you pick one, and the last one is offered first; its name appears in the summary and in the comment footers. A file too large for the model is sent as its diff only, or skipped with a note in the summary.

Command-line engines run in a temporary worktree of the pull request, get the prompt on stdin and answer on stdout. Add your own in `smartPrReview.engines` (user settings); they are merged with the bundled ones:

```json
"smartPrReview.engines": {
  "my-engine": { "command": "my-ai-cli", "args": ["--print", "--read-only"] }
}
```

The pull request content is untrusted, so give your engine the flags that stop it from writing files, running commands and browsing the web, and check that they hold in a folder the CLI has never seen: some CLIs drop their read-only mode in folders they do not trust.

## Requirements

- Git installed and the repository cloned and opened in VS Code.
- **GitHub:** an account with access to the repository. Sign in from the **Smart PR Review** view; the extension uses VS Code's built-in GitHub authentication.
- **Azure DevOps Services:** sign in with your Microsoft account, or use a personal access token with the **Code (Read & Write)** scope (command *Set Azure DevOps Personal Access Token*).
- **Azure DevOps Server (2020 or later):** add the server host to `smartPrReview.azureDevOps.hosts` and use a personal access token. HTTP(S) remotes keep their scheme and port (e.g. `http://tfs.contoso.com:8080`); SSH remotes are reached over HTTPS.

The extension reviews pull requests of the `upstream` remote when it exists (fork workflows), otherwise of `origin`.

## Settings

| Setting | Description |
|---|---|
| `smartPrReview.azureDevOps.hosts` | Azure DevOps Server host names (e.g. `tfs.contoso.com`). Machine-scoped: a repository cannot change it. |
| `smartPrReview.allowApproval` | Offer *Approve* (and on Azure DevOps *Approve with suggestions*) when publishing. Off by default so nothing is approved from VS Code by mistake; only your user settings can turn it on. |
| `smartPrReview.defaultEngine` | Engine preselected by **Review with AI**: `copilot` or a key of `smartPrReview.engines`. |
| `smartPrReview.engines` | Command-line engines (`command` + `args`). Machine-scoped. |
| `smartPrReview.profilesPath` | Folder of the review profiles, inside the repository. Default `.smart-review/profiles`. |
| `smartPrReview.ai.language` | Language of the AI comments and summary (e.g. `Spanish`). Empty: the language of the pull request, or VS Code's when unclear. |
| `smartPrReview.ai.timeoutMinutes` | Minutes an engine may take per profile, from 1 to 1440. Default 10. |
| `smartPrReview.ai.labelComments` | Add "Generated with AI · profile · engine" to AI comments. Default off. |

## Security

- Tokens are managed by VS Code's authentication providers or stored in VS Code's secret storage (Azure DevOps personal access tokens, one per host). They are never written to settings or logs, and Microsoft tokens are only sent to dev.azure.com. An Azure DevOps Server reached over plain HTTP gets your personal access token only after you accept it once for that server, since HTTP does not encrypt it.
- Pull request titles, descriptions and comments are rendered as untrusted content: no scripts, no command links.
- In untrusted workspaces (Restricted Mode) the extension stays disabled: reviewing runs git, and a repository's git configuration can make git run programs.
- AI engines treat the pull request as untrusted: the prompt says so, and every finding is a draft you review before anything is published. Command-line engines run in a temporary worktree of the pull request without the repository's git hooks and without its agent configuration (`.claude/`, `.cursor/`, `.codex/`, `.gemini/`, `.mcp.json`, `CLAUDE.md`, `AGENTS.md`, `GEMINI.md`… are removed before the engine starts, in any letter case), so the pull request cannot give the engine hooks, MCP servers or instructions. Its symbolic links are removed too, so none can pass a file outside the worktree off as part of the pull request. The bundled engines run read-only without web tools. Read-only does not stop an engine from *reading* files outside the worktree; review the drafts before publishing.
- Engine commands are machine-scoped settings, and the profiles folder must be inside the repository: a repository cannot make the extension run a program or read files outside it.

## License

MIT

## Trying the review layout

The review window separates **Files**, **Drafts** and **Conversations** into tabs with persistent filters. File badges distinguish unpublished drafts from active conversations. **Pull request details** shows the full description as plain text without executing embedded HTML. The native sidebar keeps collapsible sections for the same three tasks.

**Prepare Review** opens a preview in the existing diff group, with every draft body, the summary and the provider's supported votes. **Publish now** is the only send action. If drafts changed since the preview, it refreshes instead of sending unseen comments. File viewed state, inline editing, suggestions, AI review/cancellation, checkout and provider-specific votes are unchanged.

For a local interactive demo (no authentication, remote publication or AI calls),
run this from the repository's `vscode/` folder after `npm ci`:

```sh
SPR_UI_PREVIEW=1 npm test
```

Close the review navigator to finish the demo. Normal `npm test` runs the regression suite, not the interactive preview. To try a real repository, open this repository's root in VS Code, use **Run Extension** (F5), open the target repository in the Extension Development Host and choose **Open Review Window** on a PR.
