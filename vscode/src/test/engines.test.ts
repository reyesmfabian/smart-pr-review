import * as assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { chmod, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import * as vscode from "vscode";
import { type ChatModel, findExecutable, promptsWithinLimit, runChat, runCli, windowsCommandLine } from "../ai/engines";
import { fakeModel } from "./fakes";

/** A Node script used as a CLI engine. */
async function script(source: string): Promise<{ dir: string; path: string }> {
  const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-engine-"));
  const path = join(dir, "engine.js");
  await writeFile(path, source);
  return { dir, path };
}

const READ_STDIN = "let input = ''; process.stdin.setEncoding('utf8').on('data', c => (input += c));";

suite("AI engines", () => {
  let node: string;

  suiteSetup(async () => {
    const found = await findExecutable("node");
    assert.ok(found, "node must be in PATH to run these tests");
    node = found;
  });

  test("finds executables in PATH and ignores files that are not executable", async () => {
    const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-path-"));
    await writeFile(join(dir, "review-cli"), "#!/bin/sh\n");
    await chmod(join(dir, "review-cli"), 0o755);
    await writeFile(join(dir, "not-executable"), "");
    if (process.platform !== "win32") {
      assert.strictEqual(await findExecutable("review-cli", { PATH: dir }), join(dir, "review-cli"));
      assert.strictEqual(await findExecutable("not-executable", { PATH: dir }), undefined);
    }
    assert.strictEqual(await findExecutable("missing-cli", { PATH: dir }), undefined);
  });

  test("sends the prompt on stdin and returns stdout, running in the given folder", async () => {
    const engine = await script(`${READ_STDIN} process.stdin.on('end', () => process.stdout.write('cwd=' + process.cwd() + ' got=' + input));`);
    const output = await runCli(node, [engine.path], "hello prompt", engine.dir, new vscode.CancellationTokenSource().token, 10_000);
    assert.match(output, /got=hello prompt$/);
    assert.ok(output.includes("smart-pr-review-engine-"));
  });

  test("reports a failing engine with its stderr", async () => {
    const engine = await script("process.stderr.write('not logged in'); process.exit(3);");
    await assert.rejects(runCli(node, [engine.path], "", engine.dir, new vscode.CancellationTokenSource().token, 10_000), /code 3: not logged in/);
  });

  test("stops an engine that takes too long", async () => {
    const engine = await script("setTimeout(() => {}, 60_000);");
    await assert.rejects(runCli(node, [engine.path], "", engine.dir, new vscode.CancellationTokenSource().token, 300), /did not answer/);
  });

  test("stops an engine when the review is cancelled", async () => {
    const engine = await script("setTimeout(() => {}, 60_000);");
    const cancellation = new vscode.CancellationTokenSource();
    setTimeout(() => cancellation.cancel(), 200);
    await assert.rejects(
      runCli(node, [engine.path], "", engine.dir, cancellation.token, 10_000),
      (e: unknown) => e instanceof vscode.CancellationError,
    );
  });

  test("GitHub Copilot: streams the answer of the language model and explains why it is asked", async () => {
    const fake = fakeModel('{"summary":"ok","findings":[]}');
    assert.strictEqual(await runChat(fake.model, "the prompt", new vscode.CancellationTokenSource().token, 10_000), '{"summary":"ok","findings":[]}');
    assert.deepStrictEqual(fake.prompts, ["the prompt"]);
    assert.match(fake.justifications[0] ?? "", /review profile/);
  });

  test("GitHub Copilot: splits files to fit the window with a 10% margin, sends a too-large file as diff only, skips what still does not fit", async () => {
    const token = new vscode.CancellationTokenSource().token;
    const make = async (batch: readonly string[], withContents: boolean) => batch.map((f) => (withContents ? f.padEnd(40, ".") : f)).join("|");
    const split = await promptsWithinLimit(fakeModel("", 100).model, ["a.ts", "b.ts", "c.ts", "d.ts"], make, token);
    assert.deepStrictEqual(
      split.prompts.map((p) => p.split("|").map((s) => s.slice(0, 4))),
      [
        ["a.ts", "b.ts"],
        ["c.ts", "d.ts"],
      ],
    );
    assert.deepStrictEqual(split.skipped, []);
    // 81 tokens fit 85, but not 85 minus the margin.
    assert.strictEqual((await promptsWithinLimit(fakeModel("", 85).model, ["a.ts", "b.ts"], make, token)).prompts.length, 2);
    const big = await promptsWithinLimit(
      fakeModel("", 20).model,
      ["big.ts"],
      async (_files, withContents) => (withContents ? "x".repeat(100) : "diff"),
      token,
    );
    assert.deepStrictEqual(big, { prompts: ["diff"], skipped: [] });
    const huge = await promptsWithinLimit(fakeModel("", 20).model, ["huge.ts"], async () => "x".repeat(100), token);
    assert.deepStrictEqual(huge, { prompts: [], skipped: ["huge.ts"] });
  });

  test("GitHub Copilot: stops a request that takes longer than the timeout", async () => {
    const hanging: ChatModel = {
      id: "hang",
      name: "hang",
      maxInputTokens: 1000,
      countTokens: async (text) => text.length,
      sendRequest: (_messages, _options, token) =>
        new Promise((_resolve, reject) => token?.onCancellationRequested(() => reject(new Error("cancelled by token")))),
    };
    await assert.rejects(runChat(hanging, "p", new vscode.CancellationTokenSource().token, 200), /did not answer/);
  });

  test("GitHub Copilot: a cancelled request is reported as cancelled, not as a bad answer", async () => {
    const quitter: ChatModel = {
      id: "quit",
      name: "quit",
      maxInputTokens: 1000,
      countTokens: async (text) => text.length,
      sendRequest: async (_messages, _options, token) => ({
        text: (async function* () {
          yield '{"summ';
          await new Promise((resolve) => setTimeout(resolve, 100));
          if (!token?.isCancellationRequested) {
            yield 'ary":"","findings":[]}';
          }
        })(),
      }),
    };
    const cancellation = new vscode.CancellationTokenSource();
    setTimeout(() => cancellation.cancel(), 20);
    await assert.rejects(runChat(quitter, "p", cancellation.token, 10_000), (e: unknown) => e instanceof vscode.CancellationError);
  });

  test("on Windows, also finds commands given with their extension and prefers PATHEXT over extensionless shims", async () => {
    const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-win-"));
    // Match PATHEXT's case so this Windows simulation also runs on case-sensitive hosts.
    for (const name of ["codex.cmd", "claude", "claude.CMD"]) {
      await writeFile(join(dir, name), "");
      await chmod(join(dir, name), 0o755);
    }
    const env = { PATH: dir, PATHEXT: ".CMD" };
    assert.strictEqual(await findExecutable("codex.cmd", env, "win32"), join(dir, "codex.cmd"));
    assert.match((await findExecutable("claude", env, "win32")) ?? "", /claude\.cmd$/i);
  });

  test("quotes Windows .cmd shims the way cross-spawn does (paths with spaces, quotes in arguments)", () => {
    const line = windowsCommandLine("C:\\Program Files\\nodejs\\codex.cmd", ["exec", "-c", 'approval_policy="never"', "a b"], "cmd.exe");
    assert.deepStrictEqual(line, {
      command: "cmd.exe",
      args: ["/d", "/s", "/c", '"C:\\Program^ Files\\nodejs\\codex.cmd ^"exec^" ^"-c^" ^"approval_policy=\\^"never\\^"^" ^"a^ b^""'],
    });
  });

  test("stops the whole process tree, not only the engine", async function () {
    if (process.platform === "win32") {
      this.skip();
    }
    const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-tree-"));
    const marker = join(dir, "grandchild-survived");
    const grandchild = join(dir, "grandchild.js");
    await writeFile(grandchild, `setTimeout(() => require('fs').writeFileSync(${JSON.stringify(marker)}, 'x'), 1500);`);
    const engine = join(dir, "engine.js");
    await writeFile(
      engine,
      `require('child_process').spawn(process.execPath, [${JSON.stringify(grandchild)}], { stdio: 'ignore' }); setTimeout(() => {}, 60_000);`,
    );
    const cancellation = new vscode.CancellationTokenSource();
    setTimeout(() => cancellation.cancel(), 400);
    await assert.rejects(runCli(node, [engine], "", dir, cancellation.token, 10_000), (e: unknown) => e instanceof vscode.CancellationError);
    await new Promise((resolve) => setTimeout(resolve, 2000));
    assert.strictEqual(existsSync(marker), false);
  });

  test("kills an engine that ignores SIGTERM", async () => {
    const engine = await script("process.on('SIGTERM', () => {}); setTimeout(() => {}, 60_000);");
    const started = Date.now();
    await assert.rejects(runCli(node, [engine.path], "", engine.dir, new vscode.CancellationTokenSource().token, 300), /did not answer/);
    assert.ok(Date.now() - started < 8000);
  });

  test("stops an engine whose answer is unreasonably large", async () => {
    const engine = await script(
      "const chunk = 'x'.repeat(1 << 20); for (let i = 0; i < 6; i++) process.stdout.write(chunk); setTimeout(() => {}, 60_000);",
    );
    await assert.rejects(runCli(node, [engine.path], "", engine.dir, new vscode.CancellationTokenSource().token, 10_000), /larger than 5 MB/);
  });
});
