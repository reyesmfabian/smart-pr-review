import { join } from "node:path";
import * as vscode from "vscode";
import type { ChangedFile } from "../git";
import type { Draft, PullRequestFilter, PullRequestProvider, PullRequestSummary, Thread } from "../providers/types";
import type { ReviewSession } from "./session";

type ListNode = { kind: "group"; filter: PullRequestFilter } | { kind: "pr"; pr: PullRequestSummary };

const GROUPS: Record<PullRequestFilter, () => string> = {
  assigned: () => vscode.l10n.t("Assigned to me"),
  created: () => vscode.l10n.t("Created by me"),
  all: () => vscode.l10n.t("All open"),
};

export class PullRequestListProvider implements vscode.TreeDataProvider<ListNode> {
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.changed.event;

  constructor(
    private readonly provider: PullRequestProvider,
    /** False while signed out: the view then shows its welcome content. */
    private readonly ready: () => Promise<boolean>,
    /** Shows the error with its recovery action (e.g. "Sign In" on 401). */
    private readonly onError: (error: unknown) => void,
  ) {}

  refresh(): void {
    this.changed.fire();
  }

  getTreeItem(node: ListNode): vscode.TreeItem {
    if (node.kind === "group") {
      const state = node.filter === "all" ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.Expanded;
      return new vscode.TreeItem(GROUPS[node.filter](), state);
    }
    const item = new vscode.TreeItem(`#${node.pr.id} ${node.pr.title}`);
    item.description = node.pr.author;
    item.tooltip = node.pr.url;
    item.iconPath = new vscode.ThemeIcon("git-pull-request");
    item.contextValue = "pullRequest";
    item.command = { command: "smartPrReview.openPullRequest", title: vscode.l10n.t("Open Pull Request"), arguments: [node.pr.id] };
    return item;
  }

  async getChildren(node?: ListNode): Promise<ListNode[]> {
    if (!node) {
      const filters: PullRequestFilter[] = ["assigned", "created", "all"];
      return (await this.ready()) ? filters.map((filter): ListNode => ({ kind: "group", filter })) : [];
    }
    if (node.kind === "pr") {
      return [];
    }
    try {
      return (await this.provider.listPullRequests(node.filter)).map((pr): ListNode => ({ kind: "pr", pr }));
    } catch (error) {
      this.onError(error);
      return [];
    }
  }
}

/** The pull request's web page; undefined for anything but http(s), since the address comes from the remote. */
export function browserUri(url: string): vscode.Uri | undefined {
  const uri = vscode.Uri.parse(url);
  return uri.scheme === "https" || uri.scheme === "http" ? uri : undefined;
}

export interface SearchItem extends vscode.QuickPickItem {
  id: number;
}

/** The open pull requests, plus "Open pull request #N" first when the text is a number that is not listed. */
export function searchItems(prs: readonly PullRequestSummary[], value: string): SearchItem[] {
  const items = prs.map((pr): SearchItem => ({ id: pr.id, label: `#${pr.id} ${pr.title}`, description: pr.author }));
  const typed = /^\s*#?(\d{1,9})\s*$/.exec(value)?.[1];
  if (typed === undefined || prs.some((pr) => pr.id === Number(typed))) {
    return items;
  }
  return [{ id: Number(typed), label: `$(arrow-right) ${vscode.l10n.t("Open pull request #{0}", typed)}`, alwaysShow: true }, ...items];
}

/**
 * "Search Pull Requests": the open pull requests, filtered as you type a number, title or author; a number
 * that is not listed (closed, or older than the list) opens anyway. Resolves to the chosen id.
 */
export async function searchPullRequests(provider: PullRequestProvider): Promise<number | undefined> {
  const pick = vscode.window.createQuickPick<SearchItem>();
  pick.title = vscode.l10n.t("Search Pull Requests");
  pick.placeholder = vscode.l10n.t("Number, title or author");
  pick.matchOnDescription = true;
  pick.busy = true;
  let prs: PullRequestSummary[] = [];
  const update = () => (pick.items = searchItems(prs, pick.value));
  const chosen = new Promise<number | undefined>((resolve) => {
    pick.onDidChangeValue(update);
    pick.onDidAccept(() => {
      resolve(pick.selectedItems[0]?.id);
      pick.hide();
    });
    pick.onDidHide(() => resolve(undefined));
  });
  pick.show();
  try {
    prs = await provider.listPullRequests("all");
  } catch (error) {
    pick.dispose();
    throw error;
  }
  pick.busy = false;
  update();
  const id = await chosen;
  pick.dispose();
  return id;
}

type FileNode =
  | { kind: "files"; children: FileNode[]; count: number }
  | { kind: "conversations"; children: FileNode[]; count: number }
  | { kind: "folder"; path: string; label: string; children: FileNode[] }
  | { kind: "file"; file: ChangedFile }
  | { kind: "drafts"; drafts: Draft[] }
  | { kind: "draft"; draft: Draft }
  | { kind: "comments"; resolved: boolean; threads: Thread[] }
  | { kind: "thread"; thread: Thread };

/**
 * The changed files in their folders, folders first. A folder holding just one folder shows as one node
 * ("src/ai"), as the Explorer's compact folders do.
 */
export function fileTree(files: readonly ChangedFile[]): FileNode[] {
  type Folder = { folders: Map<string, Folder>; files: ChangedFile[] };
  const root: Folder = { folders: new Map(), files: [] };
  for (const file of files) {
    let folder = root;
    for (const part of file.path.split("/").slice(0, -1)) {
      folder = folder.folders.get(part) ?? folder.folders.set(part, { folders: new Map(), files: [] }).get(part)!;
    }
    folder.files.push(file);
  }
  const nodes = (folder: Folder, prefix: string): FileNode[] => [
    ...[...folder.folders]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([name, sub]): FileNode => {
        let label = name;
        while (sub.files.length === 0 && sub.folders.size === 1) {
          const [[next, only]] = sub.folders;
          label = `${label}/${next}`;
          sub = only;
        }
        return { kind: "folder", path: `${prefix}${label}`, label, children: nodes(sub, `${prefix}${label}/`) };
      }),
    ...[...folder.files].sort((a, b) => a.path.localeCompare(b.path)).map((file): FileNode => ({ kind: "file", file })),
  ];
  return nodes(root, "");
}

export class CurrentPullRequestProvider implements vscode.TreeDataProvider<FileNode> {
  readonly onDidChangeTreeData: vscode.Event<void>;

  constructor(private readonly session: ReviewSession) {
    this.onDidChangeTreeData = session.onDidChange;
  }

  getTreeItem(node: FileNode): vscode.TreeItem {
    switch (node.kind) {
      case "files":
      case "conversations": {
        const item = new vscode.TreeItem(
          node.kind === "files" ? vscode.l10n.t("Files") : vscode.l10n.t("Conversations"),
          vscode.TreeItemCollapsibleState.Expanded,
        );
        item.id = node.kind;
        item.description = String(node.count);
        item.iconPath = new vscode.ThemeIcon(node.kind === "files" ? "files" : "comment-discussion");
        return item;
      }
      case "folder": {
        const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.Expanded);
        item.id = `folder:${node.path}`;
        item.resourceUri = vscode.Uri.file(join(this.session.repoRoot, node.path));
        item.iconPath = vscode.ThemeIcon.Folder;
        return item;
      }
      case "file": {
        const { file } = node;
        const item = new vscode.TreeItem(vscode.Uri.file(join(this.session.repoRoot, file.path)));
        item.description = file.status;
        item.tooltip = file.oldPath ? `${file.oldPath} → ${file.path}` : file.path;
        item.checkboxState = this.session.require().viewed.has(file.path)
          ? vscode.TreeItemCheckboxState.Checked
          : vscode.TreeItemCheckboxState.Unchecked;
        item.command = { command: "smartPrReview.openFileDiff", title: vscode.l10n.t("Open Diff"), arguments: [file] };
        return item;
      }
      case "drafts": {
        const item = new vscode.TreeItem(vscode.l10n.t("Drafts ({0})", node.drafts.length), vscode.TreeItemCollapsibleState.Expanded);
        item.id = "drafts";
        item.iconPath = new vscode.ThemeIcon("edit");
        return item;
      }
      case "draft": {
        const { draft } = node;
        const place = draft.startLine === draft.endLine ? `${draft.path}:${draft.startLine}` : `${draft.path}:${draft.startLine}-${draft.endLine}`;
        const text = draft.body.split("\n").find((line) => line.trim()) ?? "";
        const item = new vscode.TreeItem(text || place);
        const severity = draft.origin === "manual" ? vscode.l10n.t("Manual") : severityLabel(draft.origin.severity);
        item.description = draft.origin === "manual" ? `${severity} · ${place}` : `${severity} · ${draft.origin.profile} · ${place}`;
        // Not trusted: an AI draft quotes the pull request.
        item.tooltip = new vscode.MarkdownString(draft.body);
        item.iconPath =
          draft.origin === "manual"
            ? new vscode.ThemeIcon("edit")
            : new vscode.ThemeIcon("sparkle", new vscode.ThemeColor(SEVERITY_COLORS[draft.origin.severity]));
        const file = this.session.current?.files.find((f) => f.path === draft.path);
        if (file) {
          item.command = { command: "smartPrReview.openFileDiff", title: vscode.l10n.t("Go to Comment"), arguments: [file, draft.startLine] };
        }
        return item;
      }
      case "comments": {
        const label = node.resolved
          ? vscode.l10n.t("Resolved comments ({0})", node.threads.length)
          : vscode.l10n.t("Active comments ({0})", node.threads.length);
        // Active ones open: they are what is left to do.
        const item = new vscode.TreeItem(label, node.resolved ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.Expanded);
        item.id = node.resolved ? "comments:resolved" : "comments:active";
        item.iconPath = threadIcon(node.resolved);
        return item;
      }
      case "thread": {
        const { thread } = node;
        const item = new vscode.TreeItem(threadPlace(thread));
        item.description = threadText(thread);
        // Not trusted: comment bodies come from the remote.
        item.tooltip = new vscode.MarkdownString(thread.comments.map((c) => `**${c.author}**: ${c.body}`).join("\n\n---\n\n"));
        item.iconPath = threadIcon(thread.isResolved);
        item.command = { command: "smartPrReview.openThread", title: vscode.l10n.t("Go to Comment"), arguments: [thread] };
        return item;
      }
    }
  }

  getChildren(node?: FileNode): FileNode[] {
    const c = this.session.current;
    if (!c) {
      return [];
    }
    if (node?.kind === "folder" || node?.kind === "files" || node?.kind === "conversations") {
      return node.children;
    }
    if (node?.kind === "comments") {
      return node.threads.map((thread): FileNode => ({ kind: "thread", thread }));
    }
    if (node?.kind === "drafts") {
      return node.drafts.map((draft): FileNode => ({ kind: "draft", draft }));
    }
    if (node) {
      return [];
    }
    const sorted = byPosition(c.threads);
    const groups = [false, true].flatMap((resolved): FileNode[] => {
      const threads = sorted.filter((t) => t.isResolved === resolved);
      return threads.length ? [{ kind: "comments", resolved, threads }] : [];
    });
    // The drafts as they are written (an AI review adds each profile's when it ends), before the comments.
    const drafts = [...c.drafts.all()].sort((a, b) => a.path.localeCompare(b.path) || a.startLine - b.startLine);
    return [
      { kind: "files", children: fileTree(c.files), count: c.files.length },
      ...(drafts.length ? [{ kind: "drafts", drafts } as const] : []),
      ...(groups.length ? [{ kind: "conversations" as const, children: groups, count: c.threads.filter((t) => !t.isResolved).length }] : []),
    ];
  }
}

export function byPosition(threads: readonly Thread[]): Thread[] {
  return [...threads].sort((a, b) => a.path.localeCompare(b.path) || (a.line ?? 0) - (b.line ?? 0));
}

/** "src/app.ts:12", or the path alone for an outdated thread, which has no line. */
export function threadPlace(thread: Thread): string {
  return thread.line && !thread.isOutdated ? `${thread.path}:${thread.line}` : thread.path;
}

/** Who opened the thread and how, marked when outdated. */
export function threadText(thread: Thread): string {
  const [first] = thread.comments;
  const text = first ? `${first.author}: ${first.body.slice(0, 80)}` : "";
  return thread.isOutdated ? `${vscode.l10n.t("outdated")} · ${text}` : text;
}

const SEVERITY_COLORS = { error: "editorError.foreground", warning: "editorWarning.foreground", info: "editorInfo.foreground" } as const;

/** The icons and colors of the Comments panel, so a thread looks the same in both places. */
function threadIcon(resolved: boolean): vscode.ThemeIcon {
  return resolved
    ? new vscode.ThemeIcon("pass", new vscode.ThemeColor("commentsView.resolvedIcon"))
    : new vscode.ThemeIcon("comment-unresolved", new vscode.ThemeColor("commentsView.unresolvedIcon"));
}

function severityLabel(severity: "error" | "warning" | "info"): string {
  return { error: vscode.l10n.t("Error"), warning: vscode.l10n.t("Warning"), info: vscode.l10n.t("Info") }[severity];
}
