import { type ChildProcess, spawn } from "node:child_process";
import { constants } from "node:fs";
import { access, stat } from "node:fs/promises";
import { delimiter, extname, isAbsolute, join } from "node:path";
import * as vscode from "vscode";

/** A command-line engine from the setting smartPrReview.engines. The prompt goes through stdin; the answer is stdout. */
export interface CliEngineConfig {
  command: string;
  args?: string[];
}

/** The part of vscode.LanguageModelChat the Copilot engine uses (tests pass a fake). */
export interface ChatModel {
  readonly id: string;
  readonly name: string;
  readonly maxInputTokens: number;
  countTokens(text: string, token?: vscode.CancellationToken): Thenable<number>;
  sendRequest(
    messages: vscode.LanguageModelChatMessage[],
    options?: vscode.LanguageModelChatRequestOptions,
    token?: vscode.CancellationToken,
  ): Thenable<{ text: AsyncIterable<string> }>;
}

/**
 * Full path of `command` found in PATH, or undefined. On Windows it tries PATHEXT, and the name as given
 * only when it already has an extension (npm also installs extensionless shell shims Windows cannot run).
 */
export async function findExecutable(
  command: string,
  env: NodeJS.ProcessEnv = process.env,
  platform: NodeJS.Platform = process.platform,
): Promise<string | undefined> {
  if (isAbsolute(command)) {
    return (await isExecutable(command)) ? command : undefined;
  }
  const pathext = (env.PATHEXT ?? ".EXE;.CMD;.BAT;.COM").split(";");
  const extensions = platform !== "win32" ? [""] : extname(command) ? ["", ...pathext] : pathext;
  for (const dir of (env.PATH ?? "").split(delimiter).filter(Boolean)) {
    for (const extension of extensions) {
      const candidate = join(dir, command + extension);
      if (await isExecutable(candidate)) {
        return candidate;
      }
    }
  }
  return undefined;
}

async function isExecutable(path: string): Promise<boolean> {
  try {
    await access(path, constants.X_OK);
    return (await stat(path)).isFile();
  } catch {
    return false;
  }
}

/** Engines answer a few kilobytes; anything far larger is a runaway process. */
const MAX_OUTPUT_MB = 5;

/**
 * Runs a CLI engine in `cwd` (the worktree), writing the prompt to stdin, and returns stdout. Never through
 * a shell (the prompt holds pull request content), except Windows .cmd/.bat shims, which need cmd.exe and
 * get cross-spawn's quoting; their arguments come from machine-scoped settings, the prompt still goes
 * through stdin. Timeouts and cancellation stop the whole process tree, and the promise settles only once
 * the engine has exited, so the worktree is never removed under a running engine.
 */
export function runCli(
  executable: string,
  args: readonly string[],
  prompt: string,
  cwd: string,
  token: vscode.CancellationToken,
  timeoutMs: number,
): Promise<string> {
  return new Promise((resolve, reject) => {
    const windows = process.platform === "win32";
    const shim = windows && /\.(cmd|bat)$/i.test(executable);
    const launch = shim ? windowsCommandLine(executable, args) : { command: executable, args: [...args] };
    // Its own process group on POSIX, so the tree can be stopped at once.
    const child = spawn(launch.command, launch.args, { cwd, windowsHide: true, windowsVerbatimArguments: shim, detached: !windows });
    let stdout = "";
    let stderr = "";
    let failure: Error | undefined;
    let escalation: NodeJS.Timeout | undefined;
    const stop = (error: Error) => {
      if (failure) {
        return;
      }
      failure = error;
      killTree(child, "SIGTERM");
      escalation = setTimeout(() => killTree(child, "SIGKILL"), 3000);
    };
    const timer = setTimeout(() => stop(new Error(vscode.l10n.t("The engine did not answer within {0} minute(s).", timeoutMs / 60000))), timeoutMs);
    const cancellation = token.onCancellationRequested(() => stop(new vscode.CancellationError()));
    const finish = () => {
      clearTimeout(timer);
      clearTimeout(escalation);
      cancellation.dispose();
    };
    child.stdout.setEncoding("utf8").on("data", (chunk: string) => {
      stdout += chunk;
      if (stdout.length > MAX_OUTPUT_MB * 1024 * 1024) {
        stop(new Error(vscode.l10n.t("The engine answer is larger than {0} MB.", MAX_OUTPUT_MB)));
      }
    });
    child.stderr.setEncoding("utf8").on("data", (chunk: string) => (stderr = (stderr + chunk).slice(-65536)));
    child.on("error", (error) => {
      finish();
      reject(error);
    });
    child.on("close", (code) => {
      finish();
      if (failure) {
        reject(failure);
      } else if (code === 0) {
        resolve(stdout);
      } else {
        reject(
          Object.assign(new Error(vscode.l10n.t("The engine exited with code {0}: {1}", code ?? "null", stderr.trim().slice(-500))), { stderr }),
        );
      }
    });
    // The engine may exit before reading all of the prompt.
    child.stdin.on("error", () => undefined);
    child.stdin.end(prompt);
  });
}

function killTree(child: ChildProcess, signal: NodeJS.Signals): void {
  if (child.pid === undefined) {
    return;
  }
  if (process.platform === "win32") {
    spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], { windowsHide: true }).on("error", () => undefined);
    return;
  }
  try {
    process.kill(-child.pid, signal);
  } catch {
    // The group is gone already.
  }
}

/** cmd.exe metacharacters, escaped with ^ (cross-spawn's set). */
const CMD_META = /([()\][%!^"`<>&|;, *?])/g;

/** cross-spawn's command line for a Windows .cmd/.bat file: MSVCRT quoting plus cmd.exe escaping. */
export function windowsCommandLine(
  executable: string,
  args: readonly string[],
  shell = process.env.comspec || "cmd.exe",
): { command: string; args: string[] } {
  // npm's local node_modules/.bin shims parse their arguments twice.
  const twice = /node_modules[\\/].bin[\\/][^\\/]+\.cmd$/i.test(executable);
  const quoted = args.map((arg) => {
    let escaped = arg.replace(/(?=(\\+?)?)\1"/g, '$1$1\\"').replace(/(?=(\\+?)?)\1$/, "$1$1");
    escaped = `"${escaped}"`.replace(CMD_META, "^$1");
    return twice ? escaped.replace(CMD_META, "^$1") : escaped;
  });
  const line = [executable.replace(CMD_META, "^$1"), ...quoted].join(" ");
  return { command: shell, args: ["/d", "/s", "/c", `"${line}"`] };
}

/** GitHub Copilot's chat models: none when Copilot is missing or signed out, or when its provider fails. */
export async function copilotModels(): Promise<ChatModel[]> {
  try {
    return await vscode.lm.selectChatModels({ vendor: "copilot" });
  } catch {
    return [];
  }
}

/** Sends the prompt and returns the whole answer; a timeout or a cancellation stops the request. */
export async function runChat(model: ChatModel, prompt: string, token: vscode.CancellationToken, timeoutMs: number): Promise<string> {
  const source = new vscode.CancellationTokenSource();
  const link = token.onCancellationRequested(() => source.cancel());
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    source.cancel();
  }, timeoutMs);
  try {
    const response = await model.sendRequest(
      [vscode.LanguageModelChatMessage.User(prompt)],
      { justification: vscode.l10n.t("Smart PR Review sends the pull request changes to the model to review them with your review profile.") },
      source.token,
    );
    let text = "";
    for await (const part of response.text) {
      text += part;
    }
    // A cancelled request may just end the stream early: a cut answer is not the model's answer.
    if (!source.token.isCancellationRequested) {
      return text;
    }
  } catch (error) {
    if (!source.token.isCancellationRequested) {
      throw error instanceof vscode.LanguageModelError ? new Error(`GitHub Copilot: ${error.message}`) : error;
    }
  } finally {
    clearTimeout(timer);
    link.dispose();
    source.dispose();
  }
  throw timedOut ? new Error(vscode.l10n.t("The engine did not answer within {0} minute(s).", timeoutMs / 60000)) : new vscode.CancellationError();
}

/**
 * Splits `files` (halving the list) until each prompt fits the model's input window, with a 10% margin
 * because token counts are estimates. A single file that does not fit is sent without its contents (diff
 * only); one that still does not fit is skipped.
 */
export async function promptsWithinLimit(
  model: ChatModel,
  files: readonly string[],
  makePrompt: (files: readonly string[], withContents: boolean) => Promise<string>,
  token: vscode.CancellationToken,
): Promise<{ prompts: string[]; skipped: string[] }> {
  const fits = async (prompt: string) => (await model.countTokens(prompt, token)) <= model.maxInputTokens * 0.9;
  const prompt = await makePrompt(files, true);
  if (await fits(prompt)) {
    return { prompts: [prompt], skipped: [] };
  }
  if (files.length <= 1) {
    const diffOnly = await makePrompt(files, false);
    return (await fits(diffOnly)) ? { prompts: [diffOnly], skipped: [] } : { prompts: [], skipped: [...files] };
  }
  const half = Math.ceil(files.length / 2);
  const first = await promptsWithinLimit(model, files.slice(0, half), makePrompt, token);
  const second = await promptsWithinLimit(model, files.slice(half), makePrompt, token);
  return { prompts: [...first.prompts, ...second.prompts], skipped: [...first.skipped, ...second.skipped] };
}
