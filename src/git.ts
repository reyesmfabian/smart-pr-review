import { execFile } from "node:child_process";
import { realpath } from "node:fs/promises";
import { devNull } from "node:os";
import { relative, resolve, sep } from "node:path";

export interface ChangedFile {
  status: "A" | "M" | "D" | "R";
  path: string;
  /** Previous path of a renamed file. */
  oldPath?: string;
}

export interface LineRange {
  /** 1-based, inclusive. */
  start: number;
  end: number;
}

export function git(cwd: string, args: readonly string[], env?: NodeJS.ProcessEnv): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile("git", args, { cwd, maxBuffer: 64 * 1024 * 1024, env: env && { ...process.env, ...env } }, (error, stdout, stderr) => {
      if (error) {
        reject(new Error(`git ${args[0]}: ${(stderr || error.message).trim()}`));
      } else {
        resolve(stdout);
      }
    });
  });
}

async function tryGit(cwd: string, args: readonly string[]): Promise<string | undefined> {
  try {
    return await git(cwd, args);
  } catch {
    return undefined;
  }
}

/**
 * Top level of the repository containing `cwd`, spelled through `cwd` when possible: git reports the
 * resolved path, but documents opened from a workspace reached through a symlink use the symlinked one.
 */
export async function repositoryRoot(cwd: string): Promise<string | undefined> {
  const top = (await tryGit(cwd, ["rev-parse", "--show-toplevel"]))?.trim();
  if (!top) {
    return undefined;
  }
  try {
    const up = relative(await realpath(cwd), await realpath(top));
    return up === "" || up.split(sep).every((part) => part === "..") ? resolve(cwd, up) : top;
  } catch {
    return top;
  }
}

/** The remote that hosts pull requests: `upstream` (fork workflows), then `origin`, then the first one. */
export async function pickRemote(cwd: string): Promise<{ name: string; url: string } | undefined> {
  const names = (await git(cwd, ["remote"])).split("\n").filter(Boolean);
  const name = ["upstream", "origin"].find((n) => names.includes(n)) ?? names[0];
  return name ? { name, url: (await git(cwd, ["remote", "get-url", name])).trim() } : undefined;
}

export async function fetchRefs(cwd: string, remote: string, refspecs: readonly string[]): Promise<void> {
  await git(cwd, ["fetch", "--no-tags", remote, ...refspecs]);
}

export async function hasCommit(cwd: string, sha: string): Promise<boolean> {
  return (await tryGit(cwd, ["cat-file", "-e", `${sha}^{commit}`])) !== undefined;
}

export async function mergeBase(cwd: string, a: string, b: string): Promise<string> {
  return (await git(cwd, ["merge-base", a, b])).trim();
}

export async function changedFiles(cwd: string, base: string, head: string): Promise<ChangedFile[]> {
  return parseNameStatus(await git(cwd, ["diff", "--name-status", "-z", "-M", base, head]));
}

export function parseNameStatus(output: string): ChangedFile[] {
  const tokens = output.split("\0");
  const files: ChangedFile[] = [];
  let i = 0;
  while (i < tokens.length && tokens[i]) {
    const code = tokens[i++][0];
    if (code === "R") {
      files.push({ status: "R", oldPath: tokens[i], path: tokens[i + 1] });
      i += 2;
    } else if (code === "C") {
      files.push({ status: "A", path: tokens[i + 1] });
      i += 2;
    } else {
      files.push({ status: code === "A" || code === "D" ? code : "M", path: tokens[i++] });
    }
  }
  return files;
}

/** Content of `path` at `sha`, or '' when the file does not exist there. */
export async function showFile(cwd: string, sha: string, path: string): Promise<string> {
  return (await tryGit(cwd, ["show", `${sha}:${path}`])) ?? "";
}

/** Head-side lines covered by the diff hunks of `file` (the lines GitHub accepts review comments on). */
export async function diffRanges(cwd: string, base: string, head: string, file: ChangedFile): Promise<LineRange[]> {
  return parseHunkRanges(await fileDiff(cwd, base, head, file, 3));
}

/** Head-side lines the pull request adds or changes in `file`: diffRanges without the context lines. */
export async function changedRanges(cwd: string, base: string, head: string, file: ChangedFile): Promise<LineRange[]> {
  return parseChangedRanges(await fileDiff(cwd, base, head, file, 0));
}

async function fileDiff(cwd: string, base: string, head: string, file: ChangedFile, context: number): Promise<string> {
  const paths = (file.oldPath ? [file.oldPath, file.path] : [file.path]).map((p) => `:(literal)${p}`);
  // Pin what GitHub's diff assumes; user config (color, context, external diff tools) must not change the ranges.
  const flags = ["--no-color", "--no-ext-diff", "--no-textconv", `-U${context}`, "--inter-hunk-context=0", "-M"];
  return git(cwd, ["diff", ...flags, base, head, "--", ...paths]);
}

const HUNK = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/gm;

/** Start and length on the head side of each hunk header. */
function hunks(diff: string): { start: number; count: number }[] {
  return [...diff.matchAll(HUNK)].map((m) => ({ start: Number(m[1]), count: m[2] === undefined ? 1 : Number(m[2]) }));
}

export function parseHunkRanges(diff: string): LineRange[] {
  return hunks(diff).flatMap(({ start, count }) => (count > 0 ? [{ start, end: start + count - 1 }] : []));
}

/**
 * The lines a -U0 diff adds or changes. "+22,0" removes lines after line 22: a comment about the removed
 * code goes on line 22 or 23, so both count.
 */
export function parseChangedRanges(diff: string): LineRange[] {
  return hunks(diff).map(({ start, count }) => (count > 0 ? { start, end: start + count - 1 } : { start: Math.max(start, 1), end: start + 1 }));
}

export async function isDirty(cwd: string): Promise<boolean> {
  return (await git(cwd, ["status", "--porcelain", "--untracked-files=no"])).trim() !== "";
}

export async function headSha(cwd: string): Promise<string> {
  return (await git(cwd, ["rev-parse", "HEAD"])).trim();
}

async function branchExists(cwd: string, branch: string): Promise<boolean> {
  return (await tryGit(cwd, ["rev-parse", "--verify", "--quiet", `refs/heads/${branch}`])) !== undefined;
}

/** False when `branch` has commits `sha` lacks, e.g. after the pull request was force-pushed. */
export async function canFastForward(cwd: string, branch: string, sha: string): Promise<boolean> {
  return !(await branchExists(cwd, branch)) || (await tryGit(cwd, ["merge-base", "--is-ancestor", `refs/heads/${branch}`, sha])) !== undefined;
}

/**
 * Checks out `branch` at `sha`: creates the branch or fast-forwards it. With `reset`, points an
 * existing branch at `sha`, discarding commits only that branch has. `upstream` ("origin/x", already
 * fetched) becomes the branch's tracking branch, so that pull and push work on it.
 */
export async function checkout(cwd: string, branch: string, sha: string, reset = false, upstream?: string): Promise<void> {
  if (reset || !(await branchExists(cwd, branch))) {
    await git(cwd, ["checkout", "-B", branch, sha]);
  } else {
    await git(cwd, ["checkout", branch]);
    await git(cwd, ["merge", "--ff-only", sha]);
  }
  if (upstream) {
    await git(cwd, ["branch", `--set-upstream-to=${upstream}`, branch]);
  }
}

/** Unified diff of `paths` (repository-relative, taken literally) with pinned options, for prompts. */
export async function diffText(cwd: string, base: string, head: string, paths: readonly string[]): Promise<string> {
  const flags = ["--no-color", "--no-ext-diff", "--no-textconv", "-U3", "-M"];
  return git(cwd, ["diff", ...flags, base, head, "--", ...paths.map((p) => `:(literal)${p}`)]);
}

/**
 * Detached worktree of `sha` at `dir` (the AI engines read the pull request there, never the user's
 * checkout). Without the repository's hooks (e.g. a post-checkout that installs dependencies) and without
 * downloading Git LFS files.
 */
export async function addWorktree(cwd: string, dir: string, sha: string): Promise<void> {
  await git(cwd, ["-c", `core.hooksPath=${devNull}`, "worktree", "add", "--detach", "--quiet", dir, sha], { GIT_LFS_SKIP_SMUDGE: "1" });
}

export async function removeWorktree(cwd: string, dir: string): Promise<void> {
  await git(cwd, ["worktree", "remove", "--force", dir]);
}

/** Forgets worktrees whose folders are gone, e.g. after VS Code was closed during a review. */
export async function pruneWorktrees(cwd: string): Promise<void> {
  await tryGit(cwd, ["worktree", "prune"]);
}
