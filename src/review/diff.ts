import { basename, join, relative, sep } from "node:path";
import * as vscode from "vscode";
import type { ChangedFile } from "../git";
import * as git from "../git";
import type { Thread } from "../providers/types";
import type { OpenPullRequest, ReviewSession } from "./session";

export const SCHEME = "smart-pr-review";

/** Read-only document with the content of `path` at commit `sha`. */
export function revisionUri(path: string, sha: string): vscode.Uri {
  return vscode.Uri.from({ scheme: SCHEME, path: `/${path}`, query: sha });
}

/** Head side of `path`: the working file when the pull request is checked out, else its revision. */
export function headUri(repoRoot: string, path: string, sha: string, checkedOut: boolean): vscode.Uri {
  return checkedOut ? vscode.Uri.file(join(repoRoot, path)) : revisionUri(path, sha);
}

/** The lines a thread is shown at: its own, or for an outdated thread those in the commit it was written against. */
export function threadLines(t: Thread): { line: number; startLine?: number } | undefined {
  return t.isOutdated ? t.original : t.line ? { line: t.line, startLine: t.startLine } : undefined;
}

/**
 * Document a review thread belongs to: the commit an outdated thread was written against; for base-side
 * threads of a renamed file, its old path, like the diff's left side.
 */
export function threadUri(c: OpenPullRequest, repoRoot: string, t: Thread): vscode.Uri {
  if (t.isOutdated && t.original) {
    return revisionUri(t.path, t.original.sha);
  }
  if (t.side === "base") {
    const renamed = c.files.find((f) => f.path === t.path && f.oldPath);
    return revisionUri(renamed?.oldPath ?? t.path, c.mergeBase);
  }
  return headUri(repoRoot, t.path, c.pr.headSha, c.checkedOut);
}

/** Repository-relative path when `uri` is the head side of a non-deleted file of the open pull request. */
export function headPathOf(session: ReviewSession, uri: vscode.Uri): string | undefined {
  const c = session.current;
  if (!c) {
    return undefined;
  }
  let path: string | undefined;
  if (uri.scheme === SCHEME && uri.query === c.pr.headSha) {
    path = uri.path.slice(1);
  } else if (uri.scheme === "file" && c.checkedOut) {
    path = relative(session.repoRoot, uri.fsPath).split(sep).join("/");
  }
  return c.files.some((f) => f.path === path && f.status !== "D") ? path : undefined;
}

/** Closes the editors that show pull request revisions (diffs and single files). */
export async function closeReviewTabs(): Promise<void> {
  const ours = (uri: vscode.Uri) => uri.scheme === SCHEME;
  const tabs = vscode.window.tabGroups.all
    .flatMap((group) => group.tabs)
    .filter(({ input }) =>
      input instanceof vscode.TabInputTextDiff
        ? ours(input.original) || ours(input.modified)
        : input instanceof vscode.TabInputText && ours(input.uri),
    );
  await vscode.window.tabGroups.close(tabs);
}

export class RevisionContentProvider implements vscode.TextDocumentContentProvider {
  constructor(private readonly repoRoot: string) {}

  provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
    // The query becomes a git argument: anything but a commit id (e.g. "--output=…") is refused.
    if (!/^[0-9a-f]{40,64}$/i.test(uri.query)) {
      return Promise.resolve("");
    }
    return git.showFile(this.repoRoot, uri.query, uri.path.slice(1));
  }
}

/** Opens the diff of `file`, at head-side `line` (1-based) or range when given, in `column` (default: the active group). */
export async function openFileDiff(
  session: ReviewSession,
  file: ChangedFile,
  line?: number | vscode.Range,
  column?: vscode.ViewColumn,
): Promise<void> {
  const c = session.require();
  const left = revisionUri(file.oldPath ?? file.path, c.mergeBase);
  const right = headUri(session.repoRoot, file.path, c.pr.headSha, c.checkedOut && file.status !== "D");
  const selection = line instanceof vscode.Range ? line : line ? lineRange(line) : undefined;
  const options: vscode.TextDocumentShowOptions = { selection, viewColumn: column };
  await vscode.commands.executeCommand("vscode.diff", left, right, `${basename(file.path)} (#${c.pr.id})`, options);
}

function lineRange(line: number): vscode.Range {
  return new vscode.Range(line - 1, 0, line - 1, 0);
}

/**
 * Shows a thread where it is: the diff at its line, or the version it lives in for a thread on the base side
 * or an outdated one (a diff only selects on the head side). An outdated thread of unknown place: its file's diff.
 */
export async function openThread(session: ReviewSession, thread: Thread, column?: vscode.ViewColumn): Promise<void> {
  const c = session.require();
  const at = threadLines(thread);
  if (at && (thread.side === "base" || thread.isOutdated)) {
    await vscode.window.showTextDocument(threadUri(c, session.repoRoot, thread), { selection: lineRange(at.line), viewColumn: column });
    return;
  }
  const file = c.files.find((f) => f.path === thread.path);
  if (file) {
    await openFileDiff(session, file, at?.line, column);
  }
}
