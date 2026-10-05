import { randomUUID } from "node:crypto";
import { mkdir, readdir, rm } from "node:fs/promises";
import { join } from "node:path";
import * as vscode from "vscode";
import type { LineRange } from "../git";
import * as git from "../git";
import type { ReviewSession } from "../review/session";
import { type ChatModel, promptsWithinLimit, runChat, runCli } from "./engines";
import { appliesTo, type Profile } from "./profiles";
import { buildPrompt, parseReviewOutput, placeFindings, type ReviewOutput } from "./review";

export type Engine = { kind: "cli"; id: string; executable: string; args: readonly string[] } | { kind: "copilot"; id: "copilot"; model: ChatModel };

/** How the engine is named in progress, summaries and comment footers. */
export function engineLabel(engine: Engine): string {
  return engine.kind === "copilot" ? `copilot (${engine.model.name})` : engine.id;
}

/** Content git shows for a binary file: never sent to a model. */
export function isBinary(text: string): boolean {
  return text.includes("\0");
}

export interface AiReviewOptions {
  session: ReviewSession;
  profiles: readonly Profile[];
  engine: Engine;
  /** Folder for the temporary worktrees of CLI engines (the extension's global storage). */
  worktrees: string;
  timeoutMs: number;
  labelComments: boolean;
  /** Language of the comments and summary (see aiLanguage). */
  language: string;
  token: vscode.CancellationToken;
  log: vscode.LogOutputChannel;
}

export interface AiReviewResult {
  drafts: number;
  /** Profiles that produced an answer (even an empty one). */
  reviewed: string[];
  failures: { profile: string; error: string }[];
}

/**
 * Reviews the open pull request with each profile in parallel and turns the findings into drafts plus a
 * summary section per profile. CLI engines read a detached worktree of the head, removed at the end.
 */
export async function runAiReview(o: AiReviewOptions): Promise<AiReviewResult> {
  const c = o.session.require();
  const root = o.session.repoRoot;
  let worktree: string | undefined;
  if (o.engine.kind === "cli") {
    // The creation time in the name lets cleanupWorktrees find folders left by a VS Code that closed mid-review.
    worktree = join(o.worktrees, `pr-${c.pr.id}-${Date.now()}-${randomUUID()}`);
    await mkdir(o.worktrees, { recursive: true });
    await git.addWorktree(root, worktree, c.pr.headSha);
    await removeAgentConfiguration(worktree);
  }
  try {
    const result: AiReviewResult = { drafts: 0, reviewed: [], failures: [] };
    // allSettled: every engine has exited before the worktree is removed, even when one is cancelled.
    const settled = await Promise.allSettled(o.profiles.map(async (profile) => ({ profile, output: await reviewWithProfile(o, profile, worktree) })));
    const outputs = settled.map((s, i) => {
      if (s.status === "fulfilled") {
        return s.value;
      }
      if (!(s.reason instanceof vscode.CancellationError)) {
        const error = s.reason instanceof Error ? s.reason.message : String(s.reason);
        const stderr = (s.reason as { stderr?: string })?.stderr?.trim();
        o.log.error(`${o.profiles[i].name} with ${engineLabel(o.engine)}: ${error}${stderr ? `\n${stderr}` : ""}`);
        result.failures.push({ profile: o.profiles[i].name, error });
      }
      return undefined;
    });
    if (settled.some((s) => s.status === "rejected" && s.reason instanceof vscode.CancellationError)) {
      throw new vscode.CancellationError();
    }
    for (const done of outputs) {
      // Profiles that match no changed file did not review anything.
      if (!done?.output) {
        continue;
      }
      // Findings belong to the pull request (and head) that was reviewed, not to whatever is open now.
      if (o.session.current !== c) {
        result.failures.push({
          profile: done.profile.name,
          error: vscode.l10n.t("The pull request changed during the review (another one was opened, or it was refreshed). Run the review again."),
        });
        continue;
      }
      result.drafts += await applyOutput(o, done.profile, done.output);
      result.reviewed.push(done.profile.name);
    }
    return result;
  } finally {
    if (worktree) {
      await git.removeWorktree(root, worktree).catch((error) => o.log.warn(`Could not remove ${worktree}: ${String(error)}`));
    }
  }
}

/** Folders and files of agent configuration and instructions that CLIs load from a repository, in lowercase. */
const AGENT_FOLDERS = new Set([".claude", ".cursor", ".codex", ".gemini"]);
const AGENT_FILES = new Set([".mcp.json", ".cursorrules", "claude.md", "claude.local.md", "agents.md", "gemini.md", "copilot-instructions.md"]);

/**
 * The worktree is the pull request's head, so its agent configuration (hooks, MCP servers, rules,
 * instructions) is written by the pull request author: remove it before an engine starts there. Names
 * match in any case (on macOS and Windows a CLI opening AGENTS.md gets AGENTS.MD), and symbolic links go
 * too: one could make a file outside the worktree look like part of the pull request.
 */
export async function removeAgentConfiguration(dir: string): Promise<void> {
  // "<mode> <object> <stage>\t<path>"; mode 120000 is a symbolic link.
  const entries = (await git.git(dir, ["ls-files", "-z", "--cached", "--stage"])).split("\0").filter(Boolean);
  const doomed = new Set<string>();
  for (const entry of entries) {
    const file = entry.slice(entry.indexOf("\t") + 1);
    const parts = file.split("/");
    const folder = parts.findIndex((part) => AGENT_FOLDERS.has(part.toLowerCase()));
    if (folder >= 0) {
      doomed.add(parts.slice(0, folder + 1).join("/"));
    } else if (entry.startsWith("120000 ") || AGENT_FILES.has(parts[parts.length - 1].toLowerCase())) {
      doomed.add(file);
    }
  }
  // rm unlinks a symbolic link without following it.
  await Promise.all([...doomed].map((path) => rm(join(dir, path), { recursive: true, force: true })));
}

async function reviewWithProfile(o: AiReviewOptions, profile: Profile, worktree: string | undefined): Promise<ReviewOutput | undefined> {
  const c = o.session.require();
  const root = o.session.repoRoot;
  const files = c.files.filter((f) => f.status !== "D" && appliesTo(profile, f.path)).map((f) => f.path);
  if (files.length === 0) {
    return undefined;
  }
  // With the old path of renamed files, so a rename is not sent as a whole new file.
  const diffOf = (paths: readonly string[]) =>
    git.diffText(
      root,
      c.mergeBase,
      c.pr.headSha,
      c.files.filter((f) => paths.includes(f.path)).flatMap((f) => (f.oldPath ? [f.oldPath, f.path] : [f.path])),
    );
  const context = { language: o.language, title: c.pr.title, description: c.pr.body };
  const parse = (output: string) => {
    try {
      return parseReviewOutput(output);
    } catch (error) {
      o.log.error(`Answer for ${profile.name} with ${engineLabel(o.engine)}:\n${output}`);
      throw error;
    }
  };
  if (o.engine.kind === "cli") {
    const prompt = buildPrompt(profile, files, await diffOf(files), context);
    o.log.trace(`Prompt for ${profile.name}:\n${prompt}`);
    return parse(await runCli(o.engine.executable, o.engine.args, prompt, worktree ?? root, o.token, o.timeoutMs));
  }
  // Copilot cannot read the repository: it gets the new version of each file too, in parts that fit its window.
  const model = o.engine.model;
  const { prompts, skipped } = await promptsWithinLimit(
    model,
    files,
    async (batch, withContents) => {
      const contents = new Map<string, string>();
      for (const path of withContents ? batch : []) {
        const text = await git.showFile(root, c.pr.headSha, path);
        if (!isBinary(text)) {
          contents.set(path, text);
        }
      }
      return buildPrompt(profile, batch, await diffOf(batch), { ...context, contents });
    },
    o.token,
  );
  const outputs: ReviewOutput[] = [];
  const errors: string[] = [];
  for (const prompt of prompts) {
    o.log.trace(`Prompt for ${profile.name}:\n${prompt}`);
    try {
      outputs.push(parse(await runChat(model, prompt, o.token, o.timeoutMs)));
    } catch (error) {
      if (error instanceof vscode.CancellationError) {
        throw error;
      }
      errors.push(error instanceof Error ? error.message : String(error));
      o.log.error(`${profile.name} with ${engineLabel(o.engine)}: ${errors[errors.length - 1]}`);
    }
  }
  if (prompts.length > 0 && errors.length === prompts.length) {
    throw new Error(errors[0]);
  }
  const notes = [
    errors.length
      ? vscode.l10n.t("{0} of {1} part(s) of this review failed: {2}", errors.length, prompts.length, [...new Set(errors)].join("; "))
      : "",
    skipped.length ? vscode.l10n.t("Not reviewed, too large for the model: {0}", skipped.join(", ")) : "",
  ];
  return { summary: [...outputs.map((p) => p.summary), ...notes].filter(Boolean).join("\n\n"), findings: outputs.flatMap((p) => p.findings) };
}

async function applyOutput(o: AiReviewOptions, profile: Profile, output: ReviewOutput): Promise<number> {
  const c = o.session.require();
  const paths = c.files.filter((f) => f.status !== "D").map((f) => f.path);
  const findings = output.findings.map((f) => ({ ...f, file: changedPath(f.file, paths) }));
  const commentable = new Map<string, LineRange[] | "all">();
  const changed = new Map<string, LineRange[]>();
  const lineCounts = new Map<string, number>();
  for (const path of new Set(findings.map((f) => f.file))) {
    const file = c.files.find((f) => f.path === path && f.status !== "D");
    if (file) {
      commentable.set(path, await o.session.commentableRanges(path));
      changed.set(path, await git.changedRanges(o.session.repoRoot, c.mergeBase, c.pr.headSha, file));
      lineCounts.set(path, countLines(await git.showFile(o.session.repoRoot, c.pr.headSha, path)));
    }
  }
  const placement = placeFindings(findings, {
    profile: profile.name,
    engine: engineLabel(o.engine),
    commentable,
    changed,
    lineCounts,
    label: o.labelComments,
  });
  for (const f of placement.discarded) {
    o.log.warn(`${profile.name}: dropped a finding about ${f.file}, which is not part of the pull request`);
  }
  for (const f of placement.unchanged) {
    o.log.warn(`${profile.name}: dropped a finding on ${f.file}:${f.line}, a line the pull request does not change: ${f.message}`);
  }
  for (const d of placement.drafts) {
    await o.session.addDraft(d.path, d.startLine, d.endLine, d.body, d.origin);
  }
  const summary = [output.summary, ...placement.summaryLines].filter(Boolean).join("\n\n");
  if (summary) {
    await o.session.appendSummary(`### ${profile.name} (${engineLabel(o.engine)})\n\n${summary}`);
  }
  return placement.drafts.length;
}

/** The changed file a finding names; engines sometimes use its absolute path in the worktree. */
function changedPath(file: string, paths: readonly string[]): string {
  if (paths.includes(file)) {
    return file;
  }
  return paths.filter((p) => file.endsWith(`/${p}`)).sort((a, b) => b.length - a.length)[0] ?? file;
}

function countLines(text: string): number {
  return text === "" ? 0 : text.split("\n").length - (text.endsWith("\n") ? 1 : 0);
}

/**
 * Removes review worktrees older than `maxAgeMs` (left when VS Code closed during a review) and forgets
 * missing ones. Younger folders may belong to a review running in another window.
 */
export async function cleanupWorktrees(repoRoot: string, folder: string, maxAgeMs: number): Promise<void> {
  for (const name of await readdir(folder).catch(() => [])) {
    const created = Number(/^pr-\d+-(\d+)-/.exec(name)?.[1]);
    if (!created || Date.now() - created < maxAgeMs) {
      continue;
    }
    const dir = join(folder, name);
    await git.removeWorktree(repoRoot, dir).catch(() => undefined);
    await rm(dir, { recursive: true, force: true });
  }
  await git.pruneWorktrees(repoRoot);
}
