import * as assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { chmod, mkdir, mkdtemp, readdir, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import * as vscode from "vscode";
import { findExecutable, type ChatModel } from "../ai/engines";
import type { Profile } from "../ai/profiles";
import { cleanupWorktrees, isBinary, removeAgentConfiguration, runAiReview, type AiReviewOptions, type Engine } from "../ai/runReview";
import { addWorktree, git } from "../git";
import { ReviewSession } from "../review/session";
import { fakeModel, FakeProvider, memento } from "./fakes";
import { makeRepo, type TestRepo } from "./repo";

const ANSWER = JSON.stringify({
  summary: "One real bug.",
  findings: [
    { file: "src/app.ts", line: 5, severity: "error", message: "Changed line is wrong", suggestion: "line 5" },
    { file: "src/app.ts", line: 10, severity: "info", message: "Unchanged line" },
    { file: "README.md", line: 1, severity: "info", message: "Not in the pull request" },
  ],
});

function profile(name: string, include: string[] = []): Profile {
  return { name, description: "", include, body: "Look for bugs.", source: "repository", local: false };
}

suite("runAiReview", () => {
  let repo: TestRepo;
  let session: ReviewSession;
  let storage: string;
  let log: vscode.LogOutputChannel;
  let node: string;

  setup(async () => {
    repo = await makeRepo();
    session = new ReviewSession(new FakeProvider(repo), repo.root, "origin", memento(), "github:octo/app");
    await session.open(1);
    storage = await mkdtemp(join(tmpdir(), "smart-pr-review-storage-"));
    log = vscode.window.createOutputChannel("Smart PR Review tests", { log: true });
    node = (await findExecutable("node"))!;
  });

  teardown(() => {
    session.dispose();
    log.dispose();
  });

  /** A CLI engine that answers `answer` and appends its working folder to the summary. */
  async function cliEngine(answer: string): Promise<Engine> {
    const path = join(storage, "engine.js");
    await writeFile(
      path,
      `let input = ''; process.stdin.on('data', c => (input += c)).on('end', () => {
        const a = JSON.parse(${JSON.stringify(answer)}); a.summary += ' cwd=' + process.cwd(); process.stdout.write('Done.\\n\`\`\`json\\n' + JSON.stringify(a) + '\\n\`\`\`');
      });`,
    );
    return { kind: "cli", id: "fake", executable: node, args: [path] };
  }

  function options(engine: Engine, profiles: Profile[]): AiReviewOptions {
    return {
      session,
      profiles,
      engine,
      worktrees: join(storage, "worktrees"),
      timeoutMs: 20_000,
      labelComments: true,
      language: "Spanish",
      token: new vscode.CancellationTokenSource().token,
      log,
    };
  }

  test("a CLI engine reviews a read-only worktree of the head; findings become drafts and summary lines", async () => {
    const result = await runAiReview(options(await cliEngine(ANSWER), [profile("Security", ["src/**"])]));
    assert.deepStrictEqual(result, { drafts: 1, reviewed: ["Security"], failures: [] });
    const [draft] = session.require().drafts.all();
    assert.deepStrictEqual(
      [draft.path, draft.startLine, draft.origin],
      ["src/app.ts", 5, { profile: "Security", engine: "fake", severity: "error" }],
    );
    assert.match(draft.body, /```suggestion\nline 5\n```\n\n_Generated with AI · Security · fake_$/);
    const summary = session.require().drafts.summary();
    assert.match(summary, /^### Security \(fake\)\n\nOne real bug\. cwd=.*smart-pr-review-storage-.*worktrees/);
    // Line 10 is not changed by the pull request: no comment, and no summary line either.
    assert.doesNotMatch(summary, /Unchanged line/);
    assert.doesNotMatch(summary, /README/);
    // The worktree is gone once the review ends.
    assert.strictEqual((await git(repo.root, ["worktree", "list"])).trim().split("\n").length, 1);
  });

  test("skips profiles that match no changed file, without calling the engine", async () => {
    const result = await runAiReview(options(await cliEngine(ANSWER), [profile("Docs only", ["nothing/**"])]));
    assert.deepStrictEqual(result, { drafts: 0, reviewed: [], failures: [] });
    assert.strictEqual(session.require().drafts.summary(), "");
  });

  test("reports a profile whose answer is not the expected JSON, and keeps the other profiles", async () => {
    const bad: Engine = { kind: "cli", id: "bad", executable: node, args: ["-e", "process.stdout.write('Sorry, no JSON')"] };
    const result = await runAiReview(options(bad, [profile("A"), profile("B")]));
    assert.deepStrictEqual(result.failures.map((f) => f.profile).sort(), ["A", "B"]);
    assert.match(result.failures[0].error, /expected JSON/);
    assert.strictEqual((await git(repo.root, ["worktree", "list"])).trim().split("\n").length, 1);
  });

  test("GitHub Copilot gets the new version of each file, since it cannot read the repository", async () => {
    const fake = fakeModel(ANSWER);
    const result = await runAiReview(options({ kind: "copilot", id: "copilot", model: fake.model }, [profile("Security", ["src/**"])]));
    assert.strictEqual(result.drafts, 1);
    assert.strictEqual(fake.prompts.length, 1);
    assert.match(fake.prompts[0], /## New version of src\/app\.ts\n\n```\n1\| line 1\n2\| line 2\n/);
    assert.match(fake.prompts[0], /\n-\| line 5\n\+5\| changed line 5\n 6\| line 6\n/);
    assert.match(fake.prompts[0], /## New version of src\/new file\.ts/);
    assert.match(fake.prompts[0], /every message in Spanish/);
    assert.match(fake.prompts[0], /## Pull request\n\nTest PR\n\nBody/);
    assert.deepStrictEqual(session.require().drafts.all()[0].origin, { profile: "Security", engine: "copilot (fake)", severity: "error" });
    assert.match(session.require().drafts.summary(), /^### Security \(copilot \(fake\)\)/);
  });

  test("removes agent configuration from the worktree, so the pull request cannot configure the engine", async () => {
    const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-agents-"));
    const files = [
      ".claude/settings.json",
      ".mcp.json",
      ".cursor/mcp.json",
      ".cursorrules",
      ".codex/config.toml",
      ".gemini/settings.json",
      "CLAUDE.md",
      "CLAUDE.local.md",
      "packages/web/CLAUDE.md",
      "AGENTS.md",
      "AGENTS.override.md",
      "GEMINI.md",
      ".github/copilot-instructions.md",
      "src/app.ts",
      // Other spellings: on macOS and Windows a CLI looking for AGENTS.md or .claude/ opens these.
      "docs/Agents.MD",
      "tools/.Claude/settings.json",
    ];
    for (const file of files) {
      await mkdir(join(dir, file, ".."), { recursive: true });
      await writeFile(join(dir, file), "x");
    }
    // Links out of the worktree: removed without touching what they point to.
    const outside = await mkdtemp(join(tmpdir(), "smart-pr-review-outside-"));
    await writeFile(join(outside, "secret"), "x");
    await symlink(join(outside, "secret"), join(dir, "src/secret"));
    await symlink(outside, join(dir, ".codex-home"));
    await symlink(outside, join(dir, ".claude-link"));
    await git(dir, ["init", "--quiet"]);
    await git(dir, ["add", "--all"]);
    await removeAgentConfiguration(dir);
    const left = (await git(dir, ["ls-files", "--cached", "--others"]))
      .trim()
      .split("\n")
      .filter((f) => existsSync(join(dir, f)));
    assert.deepStrictEqual(left, ["src/app.ts"]);
    assert.deepStrictEqual(await readdir(outside), ["secret"]);
  });

  test("never deletes through a symbolic link named like a folder of the pull request, differing only in case", async function () {
    const probe = await mkdtemp(join(tmpdir(), "smart-pr-review-case-"));
    await writeFile(join(probe, "a"), "x");
    if (!existsSync(join(probe, "A"))) {
      return this.skip();
    }
    const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-collide-"));
    await git(dir, ["init", "--quiet"]);
    await git(dir, ["config", "core.ignorecase", "false"]);
    await writeFile(join(dir, "blob"), "x");
    const blob = (await git(dir, ["hash-object", "-w", "blob"])).trim();
    await rm(join(dir, "blob"));
    // Many pairs, because the old code raced the deletion of the link against the one through it.
    const canaries: string[] = [];
    for (let i = 0; i < 50; i++) {
      const outside = await mkdtemp(join(tmpdir(), "smart-pr-review-canary-"));
      await mkdir(join(outside, ".claude"));
      canaries.push(outside);
      await symlink(outside, join(dir, `d${i}`));
      await git(dir, ["add", `d${i}`]);
      // The pull request also tracks D<i>/.claude/x, but checkout on a case-insensitive disk leaves only the link.
      await git(dir, ["update-index", "--add", "--cacheinfo", `100644,${blob},D${i}/.claude/x`]);
    }
    await removeAgentConfiguration(dir);
    for (const outside of canaries) {
      assert.ok(existsSync(join(outside, ".claude")), `${outside}/.claude was deleted through a link`);
    }
  });

  test("never deletes outside the worktree when a parent of an indexed path is a link the index does not show", async () => {
    const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-parent-"));
    await git(dir, ["init", "--quiet"]);
    await writeFile(join(dir, "blob"), "x");
    const blob = (await git(dir, ["hash-object", "-w", "blob"])).trim();
    await rm(join(dir, "blob"));
    const outside = await mkdtemp(join(tmpdir(), "smart-pr-review-canary-"));
    await mkdir(join(outside, ".claude"));
    // d is a link on disk only (not in the index, so the links-first pass does not unlink it): only the parent check stops the deletion.
    await symlink(outside, join(dir, "d"));
    await git(dir, ["update-index", "--add", "--cacheinfo", `100644,${blob},d/.claude/x`]);
    const warnings: string[] = [];
    await removeAgentConfiguration(dir, (message) => warnings.push(message));
    assert.ok(existsSync(join(outside, ".claude")), "the folder outside was deleted through a link");
    assert.strictEqual(warnings.length, 1);
  });

  test("removes agent configuration that is on disk but not in the index", async () => {
    const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-untracked-"));
    await git(dir, ["init", "--quiet"]);
    await mkdir(join(dir, "sub/.claude"), { recursive: true });
    await writeFile(join(dir, "sub/.claude/x"), "x");
    await removeAgentConfiguration(dir);
    assert.ok(!existsSync(join(dir, "sub/.claude")));
  });

  test("removes agent configuration whose names the filesystem folds to ASCII (ſ for s)", async () => {
    const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-fold-"));
    for (const file of [".curſor/x.mdc", "AGENTſ.md", "src/app.ts"]) {
      await mkdir(join(dir, file, ".."), { recursive: true });
      await writeFile(join(dir, file), "x");
    }
    await git(dir, ["init", "--quiet"]);
    await git(dir, ["add", "--all"]);
    await removeAgentConfiguration(dir);
    assert.deepStrictEqual((await readdir(dir)).filter((f) => f !== ".git").sort(), ["src"]);
  });

  test("applies nothing when another pull request was opened (or this one refreshed) during the review", async () => {
    const slow = join(storage, "slow.js");
    await writeFile(
      slow,
      `setTimeout(() => process.stdout.write(${JSON.stringify(ANSWER)}), 1500); process.stdin.resume(); process.stdin.on('end', () => {});`,
    );
    const review = runAiReview(options({ kind: "cli", id: "slow", executable: node, args: [slow] }, [profile("Security", ["src/**"])]));
    await new Promise((resolve) => setTimeout(resolve, 300));
    await session.open(1);
    const result = await review;
    assert.strictEqual(result.drafts, 0);
    assert.match(result.failures[0]?.error ?? "", /changed during the review/);
    assert.deepStrictEqual(session.require().drafts.all(), []);
    assert.strictEqual(session.require().drafts.summary(), "");
  });

  test("a cancelled review leaves no engine running and no worktree behind", async () => {
    const slow = join(storage, "forever.js");
    await writeFile(slow, "process.stdin.resume(); setTimeout(() => {}, 60_000);");
    const cancellation = new vscode.CancellationTokenSource();
    setTimeout(() => cancellation.cancel(), 300);
    const review = runAiReview({
      ...options({ kind: "cli", id: "slow", executable: node, args: [slow] }, [profile("A"), profile("B")]),
      token: cancellation.token,
    });
    await assert.rejects(review, (e: unknown) => e instanceof vscode.CancellationError);
    assert.strictEqual((await git(repo.root, ["worktree", "list"])).trim().split("\n").length, 1);
    assert.deepStrictEqual(await readdir(join(storage, "worktrees")), []);
  });

  test("cleans up worktrees older than a day, left by a VS Code that closed during a review", async () => {
    const folder = join(storage, "worktrees");
    const day = 24 * 60 * 60 * 1000;
    const old = join(folder, `pr-1-${Date.now() - 2 * day}-a`);
    const fresh = join(folder, `pr-1-${Date.now()}-b`);
    const leftover = join(folder, `pr-1-${Date.now() - 2 * day}-c`);
    await addWorktree(repo.root, old, repo.baseSha);
    await addWorktree(repo.root, fresh, repo.baseSha);
    await mkdir(leftover, { recursive: true });
    await cleanupWorktrees(repo.root, folder, day);
    assert.deepStrictEqual((await readdir(folder)).sort(), [fresh.split("/").pop()]);
    assert.strictEqual((await git(repo.root, ["worktree", "list"])).trim().split("\n").length, 2);
  });

  test("creating the worktree runs none of the repository hooks", async () => {
    const marker = join(storage, "hook-ran");
    const hook = join(repo.root, ".git", "hooks", "post-checkout");
    await writeFile(hook, `#!/bin/sh\ntouch "${marker}"\n`);
    await chmod(hook, 0o755);
    await addWorktree(repo.root, join(storage, "worktrees", "probe"), repo.baseSha);
    assert.strictEqual(existsSync(marker), false);
  });

  test("GitHub Copilot: a failing part of a split review does not lose the parts that answered", async () => {
    let calls = 0;
    const model: ChatModel = {
      id: "split",
      name: "split",
      maxInputTokens: 1000,
      // Both files together do not fit; each one alone does.
      countTokens: async (text) => (text.includes("## New version of src/app.ts") && text.includes("## New version of src/new file.ts") ? 5000 : 10),
      sendRequest: async () => {
        if (++calls === 1) {
          throw new Error("quota exceeded");
        }
        return {
          text: (async function* () {
            yield ANSWER;
          })(),
        };
      },
    };
    const result = await runAiReview(options({ kind: "copilot", id: "copilot", model }, [profile("Security", ["src/**"])]));
    assert.deepStrictEqual([result.drafts, result.failures], [1, []]);
    assert.match(session.require().drafts.summary(), /1 of 2 part\(s\) of this review failed: quota exceeded/);
  });

  test("recognizes binary content, which is never sent to the model", () => {
    assert.strictEqual(isBinary("PNG\0\x01data"), true);
    assert.strictEqual(isBinary("const a = 1;"), false);
  });

  test("logs why a profile failed, with the engine stderr, to the AI output channel", async () => {
    const errors: string[] = [];
    const spy = {
      trace: () => undefined,
      warn: () => undefined,
      error: (m: unknown) => errors.push(String(m)),
    } as unknown as vscode.LogOutputChannel;
    const failing: Engine = {
      kind: "cli",
      id: "failing",
      executable: node,
      args: ["-e", "process.stderr.write('please log in first'); process.exit(3)"],
    };
    await runAiReview({ ...options(failing, [profile("A")]), log: spy });
    assert.match(errors.join("\n"), /A with failing[\s\S]*please log in first/);
  });

  test("a renamed file reaches the engine as a rename, not as a whole new file", async () => {
    const fake = fakeModel(JSON.stringify({ summary: "", findings: [] }));
    await runAiReview(options({ kind: "copilot", id: "copilot", model: fake.model }, [profile("Docs", ["docs/**"])]));
    assert.match(fake.prompts[0], /rename from docs\/old name\.md/);
    assert.doesNotMatch(fake.prompts[0], /new file mode/);
  });

  test("accepts findings that name the file by its absolute worktree path or with Windows separators", async () => {
    const answer = JSON.stringify({
      summary: "",
      findings: [
        { file: "/somewhere/worktrees/pr-1/src/app.ts", line: 5, severity: "error", message: "Absolute" },
        { file: "src\\app.ts", line: 5, severity: "error", message: "Backslashes" },
      ],
    });
    const result = await runAiReview(options(await cliEngine(answer), [profile("Security", ["src/**"])]));
    assert.deepStrictEqual(
      session
        .require()
        .drafts.all()
        .map((d) => d.path),
      ["src/app.ts", "src/app.ts"],
    );
    assert.strictEqual(result.drafts, 2);
  });
});
