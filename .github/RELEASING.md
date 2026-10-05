# Releasing Smart PR Review

## Local checks

Use Node.js 22 (`.nvmrc`):

```sh
npm ci
npm test
npm run package
```

On headless Linux, install Xvfb and Openbox, then run
`xvfb-run -a sh -c 'openbox & npm test'`. The window manager is needed to
focus auxiliary editor windows. Tests use VS Code 1.95.0,
the oldest supported version. CI runs the same checks and retains the VSIX.

The root npm overrides apply the test runner's own patched `diff` and
`serialize-javascript` versions: npm ignores overrides declared by dependencies.
Remove them when the runner's dependency ranges include those fixes.

## One-time Marketplace setup

1. Create or confirm the Marketplace publisher. Its **ID** must exactly match
   `package.json` → `publisher` (`fabianreyes`, also used by Smart JSON Schema). The GitHub organization
   and Marketplace publisher are separate identities.
2. Create a Microsoft Entra application/service principal. Configure a federated
   credential for GitHub Actions with:
   - Issuer: `https://token.actions.githubusercontent.com`
   - Subject: `repo:reyesmfabian@57076657/smart-pr-review@1406411451:environment:marketplace`
   - Audience: `api://AzureADTokenExchange`
   GitHub uses immutable owner/repository IDs for this repository. Confirm the
   current prefix with `gh api repos/reyesmfabian/smart-pr-review/actions/oidc/customization/sub`.
3. Create the GitHub environment **marketplace**. Set environment variables
   `AZURE_CLIENT_ID` and `AZURE_TENANT_ID` from the app's client and directory IDs.
   Add a required reviewer where supported by the GitHub plan. Private repositories
   need a plan that supports environments; do not change visibility prematurely.
4. In Actions, manually run **Check Marketplace identity** on `main`. It only
   authenticates and reads the Azure DevOps profile ID; no checkout or publishing.
   Copy **Profile ID** from the run summary, add it to publisher **fabianreyes**
   with the **Contributor** role. This ID is not the application's client/object ID.
   Run it again with **verify_publisher** enabled to verify a publishing role using
   the read-only roles API (a Reader role is insufficient).
   After verification, restrict environment deployment to release tags (`v*`).

The workflow uses `azure/login` with OIDC and `vsce --azure-credential`.
No PAT, client secret, coauthor trailer or automated version commit is needed.
Do not put credentials in the repository or send them in chat.

References: [Microsoft publishing guide](https://code.visualstudio.com/api/working-with-extensions/publishing-extension#secure-automated-publishing-to-visual-studio-marketplace),
[GitHub → Azure OIDC](https://learn.microsoft.com/en-us/azure/developer/github/connect-from-azure-openid-connect).

## Publish

1. Finish the checks while the repository is private. The initial remote must
   receive only the single launch commit, not historical branches or tags.
2. Make `reyesmfabian/smart-pr-review` public at launch, so Marketplace links
   to the README, license and issue tracker are accessible.
3. Set the version in `package.json`, `package-lock.json` and `CHANGELOG.md` before
   committing. Create a GitHub release with a matching tag (for example `v0.0.1`).
   Do not mark it as a prerelease.
4. Publishing the GitHub release triggers `publish.yml`: checks the version and
   public visibility, runs lint/compile/tests, packages once, then publishes that
   exact artifact after the environment gate.
5. Verify the Actions run and Marketplace listing. The same version cannot be
   published twice; bump it for the next release. A failed run can be rerun if
   the version has not reached the Marketplace.
