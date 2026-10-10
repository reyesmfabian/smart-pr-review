# Smart PR Review — IntelliJ Platform

Review GitHub and Azure DevOps pull requests in IntelliJ IDEA and Android Studio.
The plugin supports drafts, suggestions, conversations, publishing, viewed files,
checkout, AI reviews through configured CLI engines, and persistent review worktrees.

## IntelliJ review interface

The current-PR header has explicit **Checkout Pull Request**, **Open Review Window**, and worktree buttons. The review window combines the file/draft/conversation tree with a native, read-only, two-column diff. The columns identify **Before** and **After**, with paths and commit IDs; files open at their first change. Added/deleted files also use both columns, so the diff retains its change highlighting and comment controls. Removed code is red and added code green, including the old/new sides of modified lines; these colors apply only to review viewers, without changing your global theme. Deleted files are red in the tree, additions green, modifications blue and renames purple.

## Build and test

Requires JDK 21. From the repository root:

```sh
./intellij/gradlew -p intellij test buildPlugin
```

On Windows use `intellij\gradlew.bat -p intellij test buildPlugin`.
The installable ZIP is in `intellij/build/distributions/`.
In the IDE, use **Settings → Plugins → gear icon → Install Plugin from Disk**.

To run a development IDE:

```sh
./intellij/gradlew -p intellij runIde
```

The `runAndroidStudio` task targets `/Applications/Android Studio.app` on macOS.
The plugin compiles against IntelliJ Platform 2025.1 (build 251); the configured
SDK and dependencies are downloaded by Gradle on the first build.

## Shared assets

Bundled review profiles come from [`../profiles/`](../profiles/), copied into
the plugin by Gradle. Edit the originals there, not `build/`.
The repository-wide [MIT license](../LICENSE) applies to both plugins.
See the [IntelliJ changelog](CHANGELOG.md) for version history.
See the [privacy notice](PRIVACY.md) for repository requests, local storage and optional AI processing.

CI tests and builds the plugin on Linux and Windows and retains each ZIP.
JetBrains Marketplace publication is not configured.
