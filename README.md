# Smart PR Review

Review GitHub and Azure DevOps pull requests in **VS Code** and **IntelliJ IDEA / Android Studio**: diffs, conversations, draft comments, suggestions, AI reviews, checkout and review worktrees.

## Repository layout

| Path | Contents |
|---|---|
| [`vscode/`](vscode/) | VS Code extension: TypeScript, tests, assets and Marketplace documentation |
| [`intellij/`](intellij/) | IntelliJ Platform plugin: Kotlin, tests and Gradle build |
| [`profiles/`](profiles/) | Bundled AI review profiles shared by both plugins |
| [`.github/`](.github/) | CI and release workflows |

The two plugins have independent versions and build tools. There is no root npm package.
Release histories: [VS Code changelog](vscode/CHANGELOG.md) and [IntelliJ changelog](intellij/CHANGELOG.md).

## VS Code

Requires Node.js 22 (`vscode/.nvmrc`). From the repository root:

```sh
cd vscode
npm ci
npm test
npm run package
```

Output: `vscode/smart-pr-review.vsix`. Install it with **Extensions → Install from VSIX**.
For development, open the repository root in VS Code and press **F5 → Run Extension**.
Compilation and watch startup copy the shared profiles and license into `vscode/`;
those generated copies are ignored by Git. Edit the originals at the repository root.

See the [VS Code guide](vscode/README.md) for features, authentication and settings.

## IntelliJ / Android Studio

Requires JDK 21. From the repository root:

```sh
./intellij/gradlew -p intellij test buildPlugin
```

Output: `intellij/build/distributions/*.zip`. On Windows use `intellij\gradlew.bat`.
See the [IntelliJ guide](intellij/README.md) for installation and development.

## CI and releases

- CI tests and packages both plugins, retaining the VSIX and IntelliJ ZIPs.
- Stable releases tagged `v<version>` publish **VS Code only**, matching `vscode/package.json`.
- IntelliJ release tags use `intellij-v<version>`; JetBrains Marketplace publishing is not configured.

Both plugins use the [MIT license](LICENSE).
