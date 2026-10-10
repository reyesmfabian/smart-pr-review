import * as assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { parse } from "yaml";

/** What the .vsix may contain: the compiled extension, its assets and the CommonJS build of its dependencies. */
const SHIPPED = [
  /^out\/(?!test\/).+\.js$/,
  /^profiles\/[^/]+\.md$/,
  /^resources\/[^/]+\.svg$/,
  /^l10n\/bundle\.l10n\.[\w-]+\.json$/,
  /^images\/icon\.png$/,
  /^package(\.nls(\.[\w-]+)?)?\.json$/,
  /^(README\.md|CHANGELOG\.md|LICENSE)$/,
  // dist/commonjs/package.json marks those files as CommonJS: needed.
  /^node_modules\/[^/]+\/(package\.json|LICENSE(\.md)?|dist\/(?!esm\/).+\.js(on)?)$/,
];

suite("manifest", () => {
  test("keeps independent versioned changelogs and a matching npm lockfile", async () => {
    const root = join(__dirname, "../../..");
    const manifest = JSON.parse(await readFile(join(root, "vscode/package.json"), "utf8"));
    const lock = JSON.parse(await readFile(join(root, "vscode/package-lock.json"), "utf8"));
    assert.strictEqual(lock.version, manifest.version);
    assert.strictEqual(lock.packages[""].version, manifest.version);
    const vscodeLog = await readFile(join(root, "vscode/CHANGELOG.md"), "utf8");
    assert.ok(vscodeLog.includes(`## ${manifest.version} — `));
    assert.ok(!vscodeLog.includes("- IntelliJ:"));
    const gradle = await readFile(join(root, "intellij/build.gradle.kts"), "utf8");
    const version = /^version = "([^"]+)"$/m.exec(gradle)?.[1];
    assert.ok(version);
    assert.ok((await readFile(join(root, "intellij/CHANGELOG.md"), "utf8")).includes(`## ${version} — `));
  });

  test("builds both plugins from their own folders and retains both packages in CI", async () => {
    const workflow = parse(await readFile(join(__dirname, "../../../.github/workflows/ci.yml"), "utf8"));
    const vscode = workflow.jobs.test;
    assert.strictEqual(vscode.defaults.run["working-directory"], "vscode");
    const node = vscode.steps.find((step: { uses?: string }) => step.uses?.startsWith("actions/setup-node@"));
    assert.strictEqual(node.with["node-version-file"], "vscode/.nvmrc");
    assert.strictEqual(node.with["cache-dependency-path"], "vscode/package-lock.json");
    assert.strictEqual(vscode.steps.at(-1).with.path, "vscode/smart-pr-review.vsix");
    const intellij = workflow.jobs.intellij;
    assert.strictEqual(intellij.defaults.run["working-directory"], "intellij");
    assert.deepStrictEqual(intellij.strategy.matrix.os, ["ubuntu-latest", "windows-latest"]);
    assert.ok(intellij.steps.some((step: { run?: string }) => step.run === "./gradlew test buildPlugin"));
    assert.strictEqual(intellij.steps.at(-1).with.path, "intellij/build/distributions/*.zip");
    assert.ok(intellij.steps.at(-1).with.name.includes("${{ matrix.os }}"));
  });

  test("refreshes shared build assets without retaining deleted profiles", async () => {
    const root = await mkdtemp(join(tmpdir(), "spr-build-"));
    try {
      await mkdir(join(root, "profiles"));
      await mkdir(join(root, "vscode/scripts"), { recursive: true });
      await mkdir(join(root, "vscode/profiles"));
      await copyFile(join(__dirname, "../../scripts/copy-assets.mjs"), join(root, "vscode/scripts/copy-assets.mjs"));
      await writeFile(join(root, "profiles/general.md"), "shared profile");
      await writeFile(join(root, "LICENSE"), "shared license");
      await writeFile(join(root, "vscode/profiles/obsolete.md"), "removed profile");
      await promisify(execFile)("node", [join(root, "vscode/scripts/copy-assets.mjs")], { cwd: tmpdir() });
      assert.deepStrictEqual(await readdir(join(root, "vscode/profiles")), ["general.md"]);
      assert.strictEqual(await readFile(join(root, "vscode/profiles/general.md"), "utf8"), "shared profile");
      assert.strictEqual(await readFile(join(root, "vscode/LICENSE"), "utf8"), "shared license");
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });

  test("checks the Marketplace identity manually without checking out or publishing code", async () => {
    const workflow = parse(await readFile(join(__dirname, "../../../.github/workflows/marketplace-identity.yml"), "utf8"));
    assert.deepStrictEqual(Object.keys(workflow.on), ["workflow_dispatch"]);
    assert.deepStrictEqual(workflow.permissions, {});
    const job = workflow.jobs.identity;
    assert.strictEqual(job.if, "github.ref == 'refs/heads/main'");
    assert.strictEqual(job.environment, "marketplace");
    assert.deepStrictEqual(job.permissions, { "id-token": "write" });
    assert.strictEqual(job.steps.length, 2);
    assert.strictEqual(job.steps[0].uses, "azure/login@v3.1.0");
    assert.strictEqual(job.steps[0].with["allow-no-subscriptions"], true);
    assert.ok(job.steps[1].run.includes("https://app.vssps.visualstudio.com/_apis/profile/profiles/me"));
    assert.ok(job.steps[1].run.includes("--resource 499b84ac-1321-427f-aa17-267ca6975798 --query id"));
    assert.ok(!/\bvsce\b/.test(job.steps[1].run));
    assert.strictEqual(workflow.on.workflow_dispatch.inputs.verify_publisher.default, false);
    assert.strictEqual(job.steps[1].env.VERIFY_PUBLISHER, "${{ inputs.verify_publisher }}");
    const script = job.steps[1].run;
    const mockAz = 'az() { case "$*" in *profiles/me*) echo profile-test ;; *) echo "$MOCK_ROLE" ;; esac; };\n';
    const run = (role: string, verify: string) => promisify(execFile)("sh", ["-eu", "-c", mockAz + script], {
      env: { ...process.env, GITHUB_STEP_SUMMARY: process.platform === "win32" ? "NUL" : "/dev/null", MOCK_ROLE: role, VERIFY_PUBLISHER: verify },
    });
    await run("", "false");
    for (const role of ["Contributor", "Owner", "Creator"]) {
      await run(role, "true");
    }
    for (const role of ["Reader", "", "Unexpected"]) {
      await assert.rejects(run(role, "true"));
    }
  });

  test("loads Node and Mocha globals explicitly, including in TypeScript 6 editors", async () => {
    const config = JSON.parse(await readFile(join(__dirname, "../../tsconfig.json"), "utf8"));
    assert.deepStrictEqual(config.compilerOptions.types, ["node", "mocha"]);
  });

  test("publishes the verified artifact only for a matching version and a public repository", async () => {
    const root = join(__dirname, "../..");
    const workflow = parse(await readFile(join(root, "../.github/workflows/publish.yml"), "utf8"));
    assert.strictEqual(workflow.defaults.run["working-directory"], "vscode");
    assert.strictEqual(workflow.jobs.verify.if, "${{ !github.event.release.prerelease && startsWith(github.event.release.tag_name, 'v') }}");
    for (const job of [workflow.jobs.verify, workflow.jobs.publish]) {
      const node = job.steps.find((step: { uses?: string }) => step.uses?.startsWith("actions/setup-node@"));
      assert.strictEqual(node.with["node-version-file"], "vscode/.nvmrc");
      assert.strictEqual(node.with["cache-dependency-path"], "vscode/package-lock.json");
    }
    const upload = workflow.jobs.verify.steps.find((step: { uses?: string }) => step.uses?.startsWith("actions/upload-artifact@"));
    const download = workflow.jobs.publish.steps.find((step: { uses?: string }) => step.uses?.startsWith("actions/download-artifact@"));
    assert.strictEqual(upload.with.path, "vscode/smart-pr-review.vsix");
    assert.strictEqual(download.with.path, "vscode");
    assert.strictEqual(download.with.name, upload.with.name);
    assert.strictEqual(workflow.jobs.publish.needs, "verify");
    assert.strictEqual(workflow.jobs.publish.environment, "marketplace");
    assert.strictEqual(workflow.jobs.publish.permissions["id-token"], "write");
    assert.ok(workflow.jobs.publish.steps.at(-1).run.includes("--packagePath smart-pr-review.vsix"));
    const gate = workflow.jobs.verify.steps.find((step: { name?: string }) => step.name === "Check release version and visibility");
    const script = /^node -e "([^"]+)"\s*$/.exec(gate.run)?.[1];
    assert.ok(script, "Release gate must be a runnable Node check");
    const manifest = JSON.parse(await readFile(join(root, "package.json"), "utf8"));
    const env = { ...process.env, RELEASE_TAG: `v${manifest.version}`, REPOSITORY_PRIVATE: "false" };
    const run = (overrides: Record<string, string>) => promisify(execFile)("node", ["-e", script], { cwd: root, env: { ...env, ...overrides } });
    await run({});
    await assert.rejects(run({ RELEASE_TAG: "v999.0.0" }));
    await assert.rejects(run({ RELEASE_TAG: `intellij-v${manifest.version}` }));
    await assert.rejects(run({ REPOSITORY_PRIVATE: "true" }));
  });

  test("packages only what the extension runs: no sources, tests, docs, source maps or unused dependency builds", async () => {
    // vsce needs a newer Node than VS Code's extension host: run it with the system's, as CI packages it.
    const root = join(__dirname, "../..");
    const { stdout } = await promisify(execFile)("node", [join(root, "node_modules/@vscode/vsce/vsce"), "ls"], { cwd: root });
    const files = stdout.split("\n").filter(Boolean);
    assert.ok(files.includes("out/extension.js"));
    assert.ok(files.includes("LICENSE"));
    assert.strictEqual(await readFile(join(root, "LICENSE"), "utf8"), await readFile(join(root, "../LICENSE"), "utf8"));
    const profiles = (await readdir(join(root, "../profiles"))).sort();
    assert.deepStrictEqual(files.filter((file) => file.startsWith("profiles/")).sort(), profiles.map((name) => `profiles/${name}`));
    for (const name of profiles) {
      assert.strictEqual(await readFile(join(root, "profiles", name), "utf8"), await readFile(join(root, "../profiles", name), "utf8"));
    }
    assert.deepStrictEqual(
      files.filter((file) => !SHIPPED.some((re) => re.test(file))),
      [],
    );
  });

  test("stays disabled in untrusted workspaces, where git could run repository-configured programs", async () => {
    const manifest = JSON.parse(await readFile(join(__dirname, "../../package.json"), "utf8"));
    assert.strictEqual(manifest.capabilities.untrustedWorkspaces.supported, false);
  });

  test("takes the approving votes only from user settings: a pull request could edit the workspace settings", async () => {
    const manifest = JSON.parse(await readFile(join(__dirname, "../../package.json"), "utf8"));
    assert.strictEqual(manifest.contributes.configuration.properties["smartPrReview.allowApproval"].scope, "machine");
  });

  test("ships engine templates that keep the pull request from running code (verified against the real CLIs)", async () => {
    const manifest = JSON.parse(await readFile(join(__dirname, "../../package.json"), "utf8"));
    const engines = manifest.contributes.configuration.properties["smartPrReview.engines"].default;
    // claude -p skips folder trust: without these it runs the repository's hooks and MCP servers.
    for (const flag of ["--restricted", "--strict-mcp-config", "--permission-mode"]) {
      assert.ok(engines.claude.args.includes(flag), `claude needs ${flag}`);
    }
    // Without approval_policy="never" a user hook can approve leaving the read-only sandbox.
    assert.ok(engines.codex.args.join(" ").includes('--sandbox read-only -c approval_policy="never"'));
    assert.ok(engines.cursor.args.join(" ").includes("--mode ask --sandbox enabled"));
  });
});
