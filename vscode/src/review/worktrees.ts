import { randomUUID } from "node:crypto";
import { realpath } from "node:fs/promises";
import { basename, dirname, isAbsolute, join } from "node:path";
import * as vscode from "vscode";
import * as git from "../git";

const KEY = "smart-pr-review.worktree";

export interface ReviewWorktree {
  path: string;
  headSha: string;
  prId: number;
  /** Keep the original JSON: IntelliJ and VS Code share this registry in the common local git config. */
  record: string;
}

export async function reviewWorktrees(root: string): Promise<ReviewWorktree[]> {
  const config = await git.git(root, ["config", "--local", "--null", "--list"]);
  return config.split("\0").filter((entry) => entry.startsWith(`${KEY}\n`)).map((entry) => {
    const record = entry.slice(KEY.length + 1);
    const value = JSON.parse(record) as Partial<ReviewWorktree>;
    if (typeof value.path !== "string" || !isAbsolute(value.path) || !/^[a-f0-9]{40,64}$/i.test(value.headSha ?? "") || !Number.isSafeInteger(value.prId) || value.prId! < 1) {
      throw new Error(vscode.l10n.t("Invalid review worktree registry. No files were removed."));
    }
    return { path: value.path, headSha: value.headSha!, prId: value.prId!, record };
  });
}

export async function createReviewWorktree(root: string, prId: number, headSha: string): Promise<ReviewWorktree> {
  if (!Number.isSafeInteger(prId) || prId < 1 || !/^[a-f0-9]{40,64}$/i.test(headSha)) {
    throw new Error(vscode.l10n.t("Invalid pull request revision."));
  }
  const path = join(dirname(root), `${basename(root)}-review-${prId}-${randomUUID().slice(0, 8)}`);
  const record = JSON.stringify({ path, headSha, prId });
  // Register first: even an interrupted creation remains discoverable; a failed add removes only its own record.
  await git.git(root, ["config", "--local", "--add", KEY, record]);
  try {
    await git.addWorktree(root, path, headSha);
  } catch (error) {
    await git.git(root, ["config", "--local", "--fixed-value", "--unset-all", KEY, record]);
    throw error;
  }
  return { path, headSha, prId, record };
}

/** Never accept an arbitrary directory from a UI message or a stale registry entry. */
export async function checkReviewWorktree(root: string, worktree: ReviewWorktree): Promise<void> {
  const records = await reviewWorktrees(root);
  const paths = (await git.git(root, ["worktree", "list", "--porcelain", "-z"]))
    .split("\0").filter((line) => line.startsWith("worktree ")).map((line) => line.slice(9));
  if (!records.some((w) => w.record === worktree.record && w.path === worktree.path && w.headSha === worktree.headSha) || !paths.includes(await realpath(worktree.path))) {
    throw new Error(vscode.l10n.t("This folder is no longer a registered review worktree."));
  }
}

export async function removeReviewWorktree(root: string, worktree: ReviewWorktree): Promise<void> {
  await checkReviewWorktree(root, worktree);
  if (await realpath(root) === await realpath(worktree.path)) {
    throw new Error(vscode.l10n.t("Remove this worktree from the original repository window, not from inside it."));
  }
  // Ignored files are also user data: git worktree remove alone silently deletes them.
  const status = await git.git(worktree.path, ["status", "--porcelain", "--untracked-files=all", "--ignored"]);
  const history = (await git.git(worktree.path, ["reflog", "--format=%H", "HEAD"])).trim().split("\n").filter(Boolean);
  if (status.trim() || await git.headSha(worktree.path) !== worktree.headSha || history.some((sha) => sha !== worktree.headSha)) {
    throw new Error(vscode.l10n.t("The worktree has local files, changes, or new commits. Preserve them before removing it; nothing was deleted."));
  }
  await git.git(root, ["worktree", "remove", "--", worktree.path]);
  await git.git(root, ["config", "--local", "--fixed-value", "--unset-all", KEY, worktree.record]);
}
