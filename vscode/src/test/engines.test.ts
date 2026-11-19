import * as assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { chmod, mkdtemp, writeFile } from "node:fs/promises";
import { createServer, type IncomingHttpHeaders } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import * as vscode from "vscode";
import {
  type ChatModel,
  findExecutable,
  isModelName,
  listCliModels,
  listUrlModels,
  parseModelList,
  promptsWithinLimit,
  runChat,
  runCli,
  urlChatModel,
  windowsCommandLine,
} from "../ai/engines";
import { fakeModel } from "./fakes";
import { stubFetch } from "./fetch";

/** A Node script used as a CLI engine. */
async function script(source: string): Promise<{ dir: string; path: string }> {
  const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-engine-"));
  const path = join(dir, "engine.js");
  await writeFile(path, source);
  return { dir, path };
}

/**
 * A local OpenAI-compatible server that records each request and answers with `answer` (status and JSON body);
 * without an answer it never responds. `aborted` counts the requests the client gave up on.
 */
async function chatServer(answer: () => { status?: number; body: unknown } | undefined) {
  const requests: { path: string; method: string; headers: IncomingHttpHeaders; body: Record<string, unknown> }[] = [];
  let aborted = 0;
  const server = createServer((req, res) => {
    let text = "";
    req.on("data", (chunk) => (text += chunk));
    req.on("end", () => {
      requests.push({ path: req.url ?? "", method: req.method ?? "", headers: req.headers, body: JSON.parse(text) });
      const a = answer();
      if (a) {
        res.writeHead(a.status ?? 200, { "content-type": "application/json" }).end(JSON.stringify(a.body));
      }
    });
    res.on("close", () => (aborted += res.writableEnded ? 0 : 1));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  return {
    // With a trailing slash, as users may write it.
    url: `http://127.0.0.1:${(server.address() as AddressInfo).port}/v1/`,
    requests,
    aborted: () => aborted,
    close: () => {
      server.closeAllConnections();
      return new Promise((resolve) => server.close(resolve));
    },
  };
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

  test("reads the models a CLI lists as \"id - name\" lines (the output of cursor-agent models)", () => {
    const output = [
      "Available models",
      "",
      "auto - Auto (default)",
      "gpt-5.3-codex-low - Codex 5.3 Low",
      "grok-4.7-low-fast - Grok 4.7  Low Fast\u200b\u200b",
      "\u001b[1mcomposer-2.5\u001b[0m - Composer 2.5",
      "claude-opus-4-8[context=1m,effort=high]",
      "auto - Listed twice",
      "--force - Not a model",
      "",
    ].join("\r\n");
    assert.deepStrictEqual(parseModelList(output), [
      { id: "auto", name: "Auto (default)" },
      { id: "gpt-5.3-codex-low", name: "Codex 5.3 Low" },
      { id: "grok-4.7-low-fast", name: "Grok 4.7 Low Fast" },
      { id: "composer-2.5", name: "Composer 2.5" },
      { id: "claude-opus-4-8[context=1m,effort=high]" },
    ]);
    assert.deepStrictEqual(parseModelList(""), []);
  });

  test("a model name is a single argument and never an option", () => {
    for (const name of ["sonnet", "gpt-5.2", "claude-opus-4-8[context=1m,effort=high,fast=false]", "vendor/model:tag"]) {
      assert.strictEqual(isModelName(name), true, name);
    }
    for (const name of ["", "-m", "--dangerously-skip-permissions", "two words", "tab\there", "line\nbreak", "x".repeat(201)]) {
      assert.strictEqual(isModelName(name), false, JSON.stringify(name));
    }
  });

  test("asks a CLI for its models with the arguments of the engine", async () => {
    const engine = await script("process.stdout.write('Available models\\n\\n' + process.argv.slice(2).join(',') + ' - From the arguments\\nother\\n');");
    const models = await listCliModels(node, [engine.path, "models", "--plain"], new vscode.CancellationTokenSource().token, 10_000);
    assert.deepStrictEqual(models, [{ id: "models,--plain", name: "From the arguments" }, { id: "other" }]);
    const failing = await script("process.stderr.write('not logged in'); process.exit(1);");
    await assert.rejects(listCliModels(node, [failing.path], new vscode.CancellationTokenSource().token, 10_000), /not logged in/);
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

  test("URL engines: sends the prompt with the model, max_tokens, reasoning effort and API key, and returns the answer without its reasoning", async () => {
    const json = '```json\n{"summary":"ok","findings":[]}\n```';
    const server = await chatServer(() => ({ body: { choices: [{ message: { content: `<think>maybe { this</think>\n${json}` }, finish_reason: "stop" }] } }));
    try {
      const engine = { url: server.url, contextWindow: 32768, maxOutputTokens: 8192, reasoningEffort: "medium" } as const;
      const model = urlChatModel(engine, "qwen3.8-27b", "secret");
      // The window minus what is kept for the answer; tokens estimated at 3 characters each.
      assert.strictEqual(model.maxInputTokens, 24576);
      assert.strictEqual(await model.countTokens("x".repeat(30)), 10);
      assert.strictEqual(await runChat(model, "the prompt", new vscode.CancellationTokenSource().token, 10_000), `\n${json}`);
      assert.strictEqual(server.requests[0].path, "/v1/chat/completions");
      assert.strictEqual(server.requests[0].method, "POST");
      assert.strictEqual(server.requests[0].headers.authorization, "Bearer secret");
      assert.deepStrictEqual(server.requests[0].body, {
        model: "qwen3.8-27b",
        messages: [{ role: "user", content: "the prompt" }],
        max_tokens: 8192,
        stream: false,
        reasoning_effort: "medium",
      });
    } finally {
      await server.close();
    }
  });

  test("URL engines: says why an answer was cut, a key rejected, a request refused or a server misread, naming the server", async () => {
    const answers = [
      { body: { choices: [{ message: { content: "<think>still thinking" }, finish_reason: "length" }] } },
      { status: 401, body: { error: { message: "Invalid API key" } } },
      { status: 404, body: { error: "model 'nope' not found" } },
      { body: { ok: true } },
    ];
    const server = await chatServer(() => answers.shift());
    const engine = { url: server.url, contextWindow: 8192, maxOutputTokens: 4096 };
    const review = (model: string, apiKey?: string) => runChat(urlChatModel(engine, model, apiKey), "p", new vscode.CancellationTokenSource().token, 10_000);
    try {
      await assert.rejects(review("m"), /used up maxOutputTokens \(4096\)/);
      // Without a reasoning effort the server's default applies: none is sent.
      assert.strictEqual(server.requests[0].body.reasoning_effort, undefined);
      await assert.rejects(review("m", "bad"), /127\.0\.0\.1:\d+\/v1\/ rejected the API key \(HTTP 401\)/);
      await assert.rejects(review("nope"), /answered HTTP 404: model 'nope' not found/);
      await assert.rejects(review("m"), /did not answer like an OpenAI-compatible API/);
    } finally {
      await server.close();
    }
    // A port nothing listens on any more, and never connected to: no pooled connection to it either.
    const gone = await chatServer(() => undefined);
    await gone.close();
    const refused = urlChatModel({ ...engine, url: gone.url }, "m", undefined);
    await assert.rejects(
      runChat(refused, "p", new vscode.CancellationTokenSource().token, 10_000),
      /Could not reach http:\/\/127\.0\.0\.1:\d+\/v1\/: connect ECONNREFUSED/,
    );
  });

  test("URL engines: a request that takes longer than the timeout is stopped", async () => {
    const server = await chatServer(() => undefined);
    try {
      const model = urlChatModel({ url: server.url, contextWindow: 8192, maxOutputTokens: 4096 }, "m", undefined);
      await assert.rejects(runChat(model, "p", new vscode.CancellationTokenSource().token, 200), /did not answer within/);
      for (let i = 0; i < 50 && server.aborted() === 0; i++) {
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
      assert.strictEqual(server.aborted(), 1);
    } finally {
      await server.close();
    }
  });

  test("URL engines: lists the models of the server, sending no key when there is none", async () => {
    const fetch = stubFetch(() => ({ body: { object: "list", data: [{ id: "qwen3.8-27b" }, { id: "openai/gpt-oss-20b" }, { id: "--bad" }, {}, null] } }));
    try {
      const engine = { url: "http://localhost:1234/v1", contextWindow: 8192, maxOutputTokens: 4096 };
      const models = await listUrlModels(engine, undefined, new vscode.CancellationTokenSource().token, 10_000);
      assert.deepStrictEqual(models, [{ id: "qwen3.8-27b" }, { id: "openai/gpt-oss-20b" }]);
      assert.strictEqual(fetch.calls[0].url, "http://localhost:1234/v1/models");
      assert.strictEqual((fetch.calls[0].init.headers as Record<string, string>).authorization, undefined);
    } finally {
      fetch.restore();
    }
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
