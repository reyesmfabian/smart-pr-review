# Smart PR Review — Privacy notice

Applies to the IntelliJ Platform plugin, version 0.1.0.

## Repository providers

When you open a supported Git repository, the plugin communicates with its
GitHub or Azure DevOps service to load pull requests, file changes, author names
and conversations. Authentication credentials are sent to that service, not to
a Smart PR Review server. Tokens saved by the plugin use the IDE's PasswordSafe;
the storage backend depends on your IDE configuration. An existing GitHub IDE
account may also be used. Git fetches contact your repository's remote.

Draft comments, review summaries and AI findings stay local until you choose
**Publish now**. Replies and thread actions are sent to the repository service
when you confirm them. Published reviews are subject to that service's access
controls and retention policies.

## Optional AI reviews

AI reviews run only when you request them. The plugin launches the configured
CLI with the review prompt and repository context in a temporary worktree.
This context can include source code, changes and pull request information.
The CLI may send that information to its own service, according to your account,
configuration and that provider's privacy policy. Install and authenticate the
CLI separately. Do not run an AI review if you are not authorized to share the
repository with the selected provider. CLI tools may access additional files
according to their permissions; the plugin does not sandbox third-party CLIs.

## Local storage and deletion

The IDE stores plugin settings, local drafts, viewed-file state and worktree
metadata. Review worktrees contain repository files on your machine. You can
discard unpublished drafts and remove review worktrees through the plugin;
removal refuses worktrees with local changes or additional commit history.
Remove saved credentials through your IDE credential settings and revoke tokens
with their issuing service if necessary. IDE diagnostic logs and third-party
CLI logs are governed by those applications' settings.

## Telemetry and contact

The plugin does not operate a separate analytics or telemetry backend and does
not send repository data to its author. Requests to repository services and
user-configured CLI services are necessary for the features described above.

For questions, use the public issue tracker:
https://github.com/reyesmfabian/smart-pr-review/issues.
You can also contact fabrey.bco@gmail.com.
Do not post tokens, private repository content or personal data in public issues.
