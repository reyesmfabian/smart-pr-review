import { randomBytes } from "node:crypto";
import { basename } from "node:path";
import * as vscode from "vscode";
import { cancelAiReview, onDidChangeAiReview, runningAiReview } from "../ai/commands";
import { describeError } from "../http";
import type { Thread } from "../providers/types";
import { openFileDiff, openThread } from "./diff";
import { byPosition, fileTree, threadPlace } from "./prTree";
import { PublishPanel } from "./publishPanel";
import type { OpenPullRequest, ReviewSession } from "./session";

type FileEntry =
  | { folder: string; path: string; children: FileEntry[] }
  | { file: string; name: string; status: string; viewed: boolean; drafts: number; comments: number };

interface DraftEntry {
  id: string;
  place: string;
  /** The AI profile that wrote it; empty for the reviewer's own. */
  who: string;
  text: string;
  kind: "error" | "warning" | "info" | "manual";
}

interface ThreadEntry {
  id: string;
  place: string;
  author: string;
  text: string;
  outdated: boolean;
}

/** What the page shows. It builds its DOM with textContent, so text from the pull request stays text. */
export interface ReviewData {
  number: number;
  title: string;
  author: string;
  description: string;
  viewed: number;
  total: number;
  /** "Reviewing with AI (claude)…" while an AI review runs. */
  aiReview: string | undefined;
  labels: Record<
    | "progress"
    | "files"
    | "drafts"
    | "active"
    | "resolved"
    | "publish"
    | "reviewWithAI"
    | "cancel"
    | "openInBrowser"
    | "viewed"
    | "outdated"
    | "comments"
    | "details"
    | "conversations"
    | "search"
    | "allFiles"
    | "unviewed"
    | "withPending"
    | "allOrigins"
    | "manual"
    | "allSeverities"
    | "error"
    | "warning"
    | "info"
    | "allThreads"
    | "empty"
    | "profile"
    | "severity"
    | "fileFilter"
    | "threadFilter"
    | "noDescription"
    | "unpublished"
    | "navigation"
    | "ai",
    string
  >;
  files: FileEntry[];
  drafts: DraftEntry[];
  active: ThreadEntry[];
  resolved: ThreadEntry[];
}

export function reviewData(c: OpenPullRequest): ReviewData {
  const entries = (nodes: ReturnType<typeof fileTree>): FileEntry[] =>
    nodes.flatMap((n): FileEntry[] => {
      if (n.kind === "folder") {
        return [{ folder: n.label, path: n.path, children: entries(n.children) }];
      }
      if (n.kind !== "file") {
        return [];
      }
      const { path, status } = n.file;
      return [
        {
          file: path,
          name: basename(path),
          status,
          viewed: c.viewed.has(path),
          drafts: draftCounts.get(path) ?? 0,
          comments: comments.get(path) ?? 0,
        },
      ];
    });
  // Separate unpublished work from conversations already on the provider.
  const draftCounts = new Map<string, number>();
  for (const d of c.drafts.all()) {
    draftCounts.set(d.path, (draftCounts.get(d.path) ?? 0) + 1);
  }
  const comments = new Map<string, number>();
  for (const path of c.threads.filter((t) => !t.isResolved).map((t) => t.path)) {
    comments.set(path, (comments.get(path) ?? 0) + 1);
  }
  const drafts = [...c.drafts.all()]
    .sort((a, b) => a.path.localeCompare(b.path) || a.startLine - b.startLine)
    .map(
      (d): DraftEntry => ({
        id: d.id,
        place: d.startLine === d.endLine ? `${d.path}:${d.startLine}` : `${d.path}:${d.startLine}-${d.endLine}`,
        who: d.origin === "manual" ? "" : d.origin.profile,
        text: (d.body.split("\n").find((line) => line.trim()) ?? "").slice(0, 200),
        kind: d.origin === "manual" ? "manual" : d.origin.severity,
      }),
    );
  const entry = (t: Thread): ThreadEntry => {
    const [first] = t.comments;
    return { id: t.id, place: threadPlace(t), author: first?.author ?? "", text: first?.body.slice(0, 200) ?? "", outdated: t.isOutdated };
  };
  const sorted = byPosition(c.threads);
  const viewed = c.files.filter((f) => c.viewed.has(f.path)).length;
  const total = c.files.length;
  const engine = runningAiReview();
  return {
    number: c.pr.id,
    title: c.pr.title,
    author: c.pr.author,
    description: c.pr.body,
    viewed,
    total,
    aiReview: engine === undefined ? undefined : vscode.l10n.t("Reviewing with AI ({0})…", engine),
    labels: {
      progress: viewed === total ? vscode.l10n.t("All {0} files viewed", total) : vscode.l10n.t("{0} of {1} files viewed", viewed, total),
      files: vscode.l10n.t("Files"),
      drafts: vscode.l10n.t("Drafts"),
      active: vscode.l10n.t("Active comments"),
      resolved: vscode.l10n.t("Resolved comments"),
      publish: vscode.l10n.t("Prepare review"),
      reviewWithAI: vscode.l10n.t("Review with AI"),
      cancel: vscode.l10n.t("Cancel"),
      openInBrowser: vscode.l10n.t("Open in Browser"),
      viewed: vscode.l10n.t("Viewed"),
      outdated: vscode.l10n.t("outdated"),
      comments: vscode.l10n.t("Active comments"),
      details: vscode.l10n.t("Pull request details"),
      conversations: vscode.l10n.t("Conversations"),
      search: vscode.l10n.t("Filter files or comments…"),
      allFiles: vscode.l10n.t("All files"),
      unviewed: vscode.l10n.t("Not viewed"),
      withPending: vscode.l10n.t("With drafts or active comments"),
      allOrigins: vscode.l10n.t("All authors / profiles"),
      manual: vscode.l10n.t("Manual"),
      allSeverities: vscode.l10n.t("All severities"),
      error: vscode.l10n.t("Error"),
      warning: vscode.l10n.t("Warning"),
      info: vscode.l10n.t("Info"),
      allThreads: vscode.l10n.t("All conversations"),
      empty: vscode.l10n.t("No items match these filters."),
      profile: vscode.l10n.t("Author / AI profile"),
      severity: vscode.l10n.t("Severity"),
      fileFilter: vscode.l10n.t("File status"),
      threadFilter: vscode.l10n.t("Conversation status"),
      noDescription: vscode.l10n.t("No description provided."),
      unpublished: vscode.l10n.t("Drafts are private until you publish your review."),
      navigation: vscode.l10n.t("Review navigation"),
      ai: vscode.l10n.t("AI"),
    },
    files: entries(fileTree(c.files)),
    drafts,
    active: sorted.filter((t) => !t.isResolved).map(entry),
    resolved: sorted.filter((t) => t.isResolved).map(entry),
  };
}

/** The page's buttons, as the commands they run. */
const COMMANDS = {
  reviewWithAI: "smartPrReview.reviewWithAI",
  publish: "smartPrReview.openPublishPanel",
  openInBrowser: "smartPrReview.openInBrowser",
} as const;

export type ReviewMessage =
  | { type: "ready" }
  | { type: "resized" }
  | { type: "openFile"; path: string }
  | { type: "openThread"; id: string }
  | { type: "openDraft"; id: string }
  | { type: "cancelAiReview" }
  | { type: "viewed"; path: string; viewed: boolean }
  | { type: "command"; command: keyof typeof COMMANDS };

/** Validates a message from the page: only files and threads of the pull request, only the page's buttons. */
export function parseReviewMessage(raw: unknown, c: OpenPullRequest): ReviewMessage | undefined {
  if (typeof raw !== "object" || raw === null) {
    return undefined;
  }
  const m = raw as Record<string, unknown>;
  const path = typeof m.path === "string" && c.files.some((f) => f.path === m.path) ? m.path : undefined;
  switch (m.type) {
    case "ready":
      return { type: "ready" };
    case "resized":
      return { type: "resized" };
    case "openFile":
      return path === undefined ? undefined : { type: "openFile", path };
    case "viewed":
      return path === undefined || typeof m.viewed !== "boolean" ? undefined : { type: "viewed", path, viewed: m.viewed };
    case "openThread":
      return typeof m.id === "string" && c.threads.some((t) => t.id === m.id) ? { type: "openThread", id: m.id } : undefined;
    case "openDraft":
      return typeof m.id === "string" && c.drafts.all().some((d) => d.id === m.id) ? { type: "openDraft", id: m.id } : undefined;
    case "cancelAiReview":
      return { type: "cancelAiReview" };
    case "command":
      return typeof m.command === "string" && Object.hasOwn(COMMANDS, m.command)
        ? { type: "command", command: m.command as keyof typeof COMMANDS }
        : undefined;
  }
  return undefined;
}

/**
 * The review in a window of its own: the pull request's files, drafts and comments, and the diffs they open
 * beside them in that window. The side bar views stay in the main window: a window of editors has none.
 */
export class ReviewWindow implements vscode.Disposable {
  private static instance: ReviewWindow | undefined;
  private readonly disposables: vscode.Disposable[] = [];
  /**
   * The group the page opens diffs in. "Beside" alone means beside the active group, which after the first
   * diff is that diff's: each file would open one more group to the right.
   */
  private diffColumn: vscode.ViewColumn | undefined;
  private layoutReady = false;
  /** The page's share of its window's width since it was sized: set by us, or by the user dragging the sash. */
  private split: number | undefined;
  private sizing = false;

  static async show(session: ReviewSession, storage?: vscode.Memento): Promise<void> {
    session.require();
    if (ReviewWindow.instance) {
      ReviewWindow.instance.panel.reveal();
      return;
    }
    const panel = vscode.window.createWebviewPanel("smartPrReview.review", vscode.l10n.t("Review"), vscode.ViewColumn.Active, {
      enableScripts: true,
      localResourceRoots: [],
      retainContextWhenHidden: true,
    });
    const window = new ReviewWindow(panel, session, storage);
    ReviewWindow.instance = window;
    // VS Code 1.85+: the active editor, this page, moves to a new window.
    await vscode.commands.executeCommand("workbench.action.moveEditorToNewWindow");
    // Laid out in that window, now the active one (the layout command only touches it): a narrow page
    // and a wide group beside it, where the diffs go.
    await vscode.commands.executeCommand("vscode.setEditorLayout", { orientation: 0, groups: [{ size: 0.3 }, { size: 0.7 }] });
    const page = vscode.window.tabGroups.all.find((g) =>
      g.tabs.some((t) => t.input instanceof vscode.TabInputWebview && t.input.viewType.endsWith("smartPrReview.review")),
    );
    window.diffColumn = page && page.viewColumn + 1;
    window.layoutReady = true;
    await window.keepWidth();
  }

  private constructor(
    private readonly panel: vscode.WebviewPanel,
    private readonly session: ReviewSession,
    private readonly storage?: vscode.Memento,
  ) {
    panel.webview.html = renderReviewHtml(randomBytes(16).toString("base64"));
    this.disposables.push(
      panel.webview.onDidReceiveMessage((raw) => this.onMessage(raw)),
      session.onDidChange(() => this.update()),
      onDidChangeAiReview(() => this.update()),
      panel.onDidDispose(() => this.dispose()),
      panel.onDidChangeViewState(() => {
        if (panel.active && this.layoutReady && this.split === undefined) {
          void this.keepWidth();
        }
      }),
    );
    this.update();
  }

  dispose(): void {
    ReviewWindow.instance = undefined;
    this.disposables.forEach((d) => d.dispose());
  }

  private update(): void {
    const c = this.session.current;
    if (!c) {
      // The review was exited.
      this.panel.dispose();
      return;
    }
    this.panel.title = `#${c.pr.id} ${c.pr.title}`;
    void this.panel.webview.postMessage({ type: "update", data: reviewData(c) });
  }

  /** Opens in the page's diff group, or beside the page while it has none (or it was closed). */
  private async inDiffGroup(open: (column: vscode.ViewColumn) => Promise<void>): Promise<void> {
    const column = this.diffColumn;
    const exists = column !== undefined && column !== this.panel.viewColumn && vscode.window.tabGroups.all.some((g) => g.viewColumn === column);
    await open(exists ? column : vscode.ViewColumn.Beside);
    this.diffColumn = vscode.window.tabGroups.activeTabGroup.viewColumn;
  }

  /**
   * Keeps the page at its width in pixels, like a side bar (see navigatorSplit). Only in our window holding
   * just the page and the diffs: never a different window or a layout the user made.
   */
  private async keepWidth(): Promise<void> {
    if (!this.layoutReady || this.sizing || !this.panel.visible) {
      return;
    }
    // Held from the first read: a check started meanwhile would read the layout from before ours applies.
    this.sizing = true;
    try {
      // The layout commands act on the active window.
      const active = vscode.window.tabGroups.activeTabGroup.viewColumn;
      const layout = await vscode.commands.executeCommand<{ groups: { size: number; groups?: unknown[] }[] }>("vscode.getEditorLayout");
      if ((active !== this.panel.viewColumn && active !== this.diffColumn) || layout?.groups.length !== 2 || layout.groups.some((g) => g.groups)) {
        return;
      }
      // getEditorLayout gives the groups' widths in pixels, already laid out: the page's own measure can lag behind.
      const [page, diffs] = layout.groups.map((g) => g.size);
      const { split, remember } = navigatorSplit(page, page + diffs, this.split, this.storage?.get<number>(WIDTH_KEY));
      this.split = split;
      if (remember !== undefined) {
        await this.storage?.update(WIDTH_KEY, remember);
      } else if (Math.abs(split * (page + diffs) - page) >= 2) {
        await vscode.commands.executeCommand("vscode.setEditorLayout", { orientation: 0, groups: [{ size: split }, { size: 1 - split }] });
      }
    } finally {
      this.sizing = false;
    }
  }

  private async onMessage(raw: unknown): Promise<void> {
    const c = this.session.current;
    const message = c && parseReviewMessage(raw, c);
    if (!c || !message) {
      return;
    }
    try {
      switch (message.type) {
        case "ready":
          // The page loaded, or reloaded after moving to another window.
          this.update();
          return;
        case "resized":
          return await this.keepWidth();
        case "openFile":
          return await this.inDiffGroup((column) => openFileDiff(this.session, c.files.find((f) => f.path === message.path)!, undefined, column));
        case "openThread":
          return await this.inDiffGroup((column) => openThread(this.session, c.threads.find((t) => t.id === message.id)!, column));
        case "openDraft": {
          const draft = c.drafts.all().find((d) => d.id === message.id)!;
          const file = c.files.find((f) => f.path === draft.path);
          return file && (await this.inDiffGroup((column) => openFileDiff(this.session, file, draft.startLine, column)));
        }
        case "cancelAiReview":
          return cancelAiReview();
        case "viewed":
          return await this.session.setViewed(message.path, message.viewed);
        case "command":
          if (message.command === "publish") {
            return await this.inDiffGroup(async (column) => PublishPanel.show(this.session, column));
          }
          await vscode.commands.executeCommand(COMMANDS[message.command]);
      }
    } catch (error) {
      void vscode.window.showErrorMessage(describeError(error));
    }
  }
}

/** Where the width the user dragged the page to is kept; earlier keys also recorded window resizes. */
const WIDTH_KEY = "smartPrReview.reviewPageWidth";

/** The narrowest the page is kept: "Conversaciones" still fits in its tab. */
const MIN_WIDTH = 260;

/** A compact width, or the one the user dragged the page to, never more than 40% of the window. */
export function navigatorFraction(totalWidth: number, savedWidth?: number): number {
  const width = savedWidth !== undefined && Number.isFinite(savedWidth) ? Math.max(MIN_WIDTH, Math.min(600, savedWidth)) : 280;
  return Number.isFinite(totalWidth) && totalWidth > 0 ? Math.min(0.4, width / totalWidth) : 0.3;
}

/**
 * The page keeps a width in pixels, like a side bar. Given its group's and its window's widths (`page` of `total`)
 * and the split it was left at: a moved split is the user dragging the sash, a width to remember; anything else
 * (the window opening, maximized or resized, which keeps the split) gets the split that restores the width.
 * A shrinking window can move the split too, holding the page at VS Code's minimum for a group: below ours,
 * that is not a width to keep.
 */
export function navigatorSplit(
  page: number,
  total: number,
  last: number | undefined,
  saved: number | undefined,
): { split: number; remember?: number } {
  if (last !== undefined && Math.abs(page - last * total) > 2 && page >= MIN_WIDTH) {
    return { split: page / total, remember: page };
  }
  return { split: navigatorFraction(total, saved) };
}

/** Codicon-like 16px icons, drawn with the text color. */
const ICONS = {
  chevron: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3.5 10.5 8 6 12.5"/></svg>',
  sparkle: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.75 9.3 6.7 14.25 8 9.3 9.3 8 14.25 6.7 9.3 1.75 8 6.7 6.7Z"/></svg>',
  send: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2 2.5 14 8 2 13.5 4.2 8ZM4.2 8H9"/></svg>',
  external:
    '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M9.5 2.5h4v4M13.5 2.5l-6 6M12 9.5V13a.5.5 0 0 1-.5.5h-8A.5.5 0 0 1 3 13V5a.5.5 0 0 1 .5-.5H7"/></svg>',
  unresolved:
    '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 3.5a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H8l-3 3v-3H3.5a1 1 0 0 1-1-1Z"/></svg>',
  resolved: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 8.5 6.5 12 13 4.5"/></svg>',
  edit: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10.5 2.5l3 3-8 8h-3v-3Z"/></svg>',
  spinner: '<svg viewBox="0 0 16 16" aria-hidden="true" class="spin"><path d="M8 2a6 6 0 1 0 6 6"/></svg>',
};

export function renderReviewHtml(nonce: string): string {
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<style nonce="${nonce}">
  [hidden] { display: none !important; }
  body { margin: 0; padding: 0 16px 24px; max-width: 760px; font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); color: var(--vscode-foreground); }
  svg { width: 16px; height: 16px; flex-shrink: 0; fill: none; stroke: currentColor; stroke-width: 1.2; stroke-linecap: round; stroke-linejoin: round; }
  button { font: inherit; cursor: pointer; }
  :focus-visible { outline: 1px solid var(--vscode-focusBorder); outline-offset: -1px; }

  header { padding: 16px 0 14px; }
  .heading { display: flex; align-items: flex-start; gap: 8px; }
  h1 { flex: 1; margin: 0; font-size: 18px; font-weight: 600; line-height: 1.35; overflow-wrap: anywhere; }
  .number { font-weight: 400; color: var(--vscode-descriptionForeground); }
  .author { margin: 2px 0 14px; color: var(--vscode-descriptionForeground); }
  .icon-button { display: grid; place-items: center; width: 26px; height: 26px; flex-shrink: 0; padding: 0; border: none; border-radius: 5px; background: transparent; color: var(--vscode-icon-foreground); }
  .icon-button:hover { background: var(--vscode-toolbar-hoverBackground); }
  .actions { display: flex; flex-wrap: wrap; gap: 8px; }
  .button { display: inline-flex; align-items: center; gap: 6px; height: 26px; padding: 0 12px; border: 1px solid var(--vscode-button-border, transparent); border-radius: 2px; }
  .button.primary { color: var(--vscode-button-foreground); background: var(--vscode-button-background); }
  .button.primary:hover { background: var(--vscode-button-hoverBackground); }
  .button.secondary { color: var(--vscode-button-secondaryForeground); background: var(--vscode-button-secondaryBackground); }
  .button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
  .button:focus-visible, .icon-button:focus-visible { outline-offset: 2px; }
  .button:disabled { opacity: .6; cursor: default; }
  .spin { animation: spin 1s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .count { min-width: 18px; padding: 0 5px; border-radius: 9px; font-size: 11px; line-height: 16px; text-align: center; background: color-mix(in srgb, currentColor 22%, transparent); }

  /* Review progress: the one thing the page shows big. */
  .progress { height: 4px; margin-top: 18px; border-radius: 2px; overflow: hidden; background: color-mix(in srgb, var(--vscode-progressBar-background) 22%, transparent); }
  .fill { width: 0; height: 100%; border-radius: 2px; background: var(--vscode-progressBar-background); transition: width .25s ease-out; }
  .progress.done .fill { background: var(--vscode-testing-iconPassed); }
  .progress-label { margin: 6px 0 0; font-size: 12px; color: var(--vscode-descriptionForeground); }
  .progress.done + .progress-label { color: var(--vscode-testing-iconPassed); }

  summary::-webkit-details-marker { display: none; }
  details > summary > .chevron svg { transition: transform .1s ease-out; }
  details[open] > summary > .chevron svg { transform: rotate(90deg); }
  .badge { margin-left: 4px; min-width: 18px; padding: 0 6px; border-radius: 9px; font-size: 11px; font-weight: 400; line-height: 18px; text-align: center; color: var(--vscode-badge-foreground); background: var(--vscode-badge-background); }

  ul { list-style: none; margin: 0; padding: 0; }
  .row { display: flex; align-items: center; gap: 6px; min-height: 22px; padding: 0 8px 0 calc(8px + var(--depth, 0) * 16px); border-radius: 3px; cursor: pointer; white-space: nowrap; list-style: none; }
  .row:hover { background: var(--vscode-list-hoverBackground); }
  .row.selected { color: var(--vscode-list-inactiveSelectionForeground, inherit); background: var(--vscode-list-inactiveSelectionBackground); }
  .row.selected:focus { color: var(--vscode-list-activeSelectionForeground); background: var(--vscode-list-activeSelectionBackground); }
  .icon { display: grid; place-items: center; }
  .folder .name { color: var(--vscode-foreground); }
  .name { overflow: hidden; text-overflow: ellipsis; }
  .viewed .name { color: var(--vscode-descriptionForeground); }
  .status { padding-left: 10px; font-size: 11px; font-weight: 600; }
  .file .name { margin-right: auto; }
  .file-row { display: flex; align-items: center; gap: 6px; padding-left: calc(8px + var(--depth, 0) * 16px); }
  button.file { flex: 1; min-width: 0; border: 0; background: transparent; color: inherit; text-align: left; padding-left: 0; }
  .comments { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; color: var(--vscode-descriptionForeground); }
  .comments svg { width: 14px; height: 14px; }
  .status.M { color: var(--vscode-gitDecoration-modifiedResourceForeground); }
  .status.A { color: var(--vscode-gitDecoration-addedResourceForeground); }
  .status.D { color: var(--vscode-gitDecoration-deletedResourceForeground); }
  .status.R { color: var(--vscode-gitDecoration-renamedResourceForeground); }

  /* The checkbox of the theme, not the browser's. */
  input[type=checkbox] { appearance: none; display: grid; place-content: center; width: 16px; height: 16px; margin: 0; flex-shrink: 0; border: 1px solid var(--vscode-checkbox-border); border-radius: 3px; background: var(--vscode-checkbox-background); cursor: pointer; }
  input[type=checkbox]::before { content: ''; width: 8px; height: 4px; border: solid var(--vscode-checkbox-foreground); border-width: 0 0 1.5px 1.5px; transform: translateY(-1px) rotate(-45deg); opacity: 0; }
  input[type=checkbox]:checked::before { opacity: 1; }
  input[type=checkbox]:focus-visible { outline-offset: 1px; }

  .thread { align-items: flex-start; padding-top: 4px; padding-bottom: 4px; }
  .thread .icon { height: 18px; }
  .thread .icon.unresolved { color: var(--vscode-commentsView-unresolvedIcon); }
  .thread .icon.resolved { color: var(--vscode-commentsView-resolvedIcon); }
  .thread .icon.error { color: var(--vscode-editorError-foreground); }
  .thread .icon.warning { color: var(--vscode-editorWarning-foreground); }
  .thread .icon.info { color: var(--vscode-editorInfo-foreground); }
  .thread .icon.manual { color: var(--vscode-descriptionForeground); }
  .lines { min-width: 0; line-height: 18px; }
  .lines > div { overflow: hidden; text-overflow: ellipsis; }
  .text { color: var(--vscode-descriptionForeground); }
  .who { margin-right: 6px; font-weight: 600; color: var(--vscode-foreground); }
  .tag { margin-left: 8px; padding: 0 6px; border: 1px solid var(--vscode-descriptionForeground); border-radius: 9px; font-size: 11px; line-height: 15px; color: var(--vscode-descriptionForeground); }

  html { height: 100%; }
  body { box-sizing: border-box; height: 100vh; max-width: none; padding: 0 12px; display: flex; flex-direction: column; overflow: hidden; }
  header { flex: none; padding: 12px 0 10px; }
  h1 { font-size: 15px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .author { margin-bottom: 8px; font-size: 12px; }
  .progress { margin-top: 12px; }
  .context { margin-top: 8px; font-size: 12px; }
  .context summary { cursor: pointer; color: var(--vscode-textLink-foreground); }
  .description { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 180px; overflow: auto; line-height: 1.5; margin: 8px 0; }
  .tabs { display: flex; flex: none; border-bottom: 1px solid var(--vscode-panel-border); }
  .tab { flex: 1; min-width: 0; padding: 8px 2px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--vscode-descriptionForeground); font-size: 12px; }
  .tab[aria-selected=true] { color: var(--vscode-foreground); border-bottom-color: var(--vscode-focusBorder); }
  .tab:hover { background: var(--vscode-list-hoverBackground); }
  .badge { display: inline-block; padding: 0 4px; min-width: 10px; margin-left: 3px; }
  .filters { flex: none; display: flex; gap: 6px; padding: 8px 0; flex-wrap: wrap; }
  select, input[type=search] { min-width: 0; box-sizing: border-box; width: 100%; padding: 5px 6px; font: inherit; font-size: 12px; color: var(--vscode-input-foreground); background: var(--vscode-input-background); border: 1px solid var(--vscode-input-border, var(--vscode-panel-border)); border-radius: 2px; }
  .filters select { flex: 1; }
  main { flex: 1; min-height: 0; overflow: auto; padding-bottom: 8px; }
  .panel { outline-offset: -1px; }
  .row { min-height: 28px; padding-right: 4px; }
  .thread { padding: 10px 6px; white-space: normal; border-radius: 0; border-bottom: 1px solid var(--vscode-panel-border); }
  .lines { flex: 1; }
  .thread .text { color: var(--vscode-foreground); display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
  .place { color: var(--vscode-descriptionForeground); font-size: 11px; margin-top: 4px; white-space: nowrap; }
  .metadata { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; font-size: 11px; color: var(--vscode-descriptionForeground); margin-top: 4px; }
  .severity { padding: 0 4px; border: 1px solid currentColor; border-radius: 3px; }
  .severity.error { color: var(--vscode-editorError-foreground); }
  .severity.warning { color: var(--vscode-editorWarning-foreground); }
  .severity.info { color: var(--vscode-editorInfo-foreground); }
  .empty, .hint { color: var(--vscode-descriptionForeground); font-size: 12px; line-height: 1.5; }
  .hint { margin: 6px 0; }
  footer { flex: none; padding: 10px 0; border-top: 1px solid var(--vscode-panel-border); }
  footer .button { width: 100%; justify-content: center; min-height: 30px; }
  .ai-status { color: var(--vscode-descriptionForeground); font-size: 12px; margin: 8px 0 0; overflow-wrap: anywhere; }
  @media (prefers-reduced-motion: reduce) { * { transition: none !important; animation: none !important; } }
</style>
</head>
<body>
<header>
  <div class="heading">
    <h1 id="heading"><span class="number" id="number"></span> <span id="title"></span></h1>
    <button class="icon-button" id="openInBrowser" data-command="openInBrowser">${ICONS.external}</button>
  </div>
  <p class="author" id="author"></p>
  <details class="context" id="details"><summary id="detailsLabel"></summary><div class="description" id="description"></div></details>
  <div class="progress" id="progress" role="progressbar" aria-valuemin="0"><div class="fill" id="progressFill"></div></div>
  <p class="progress-label" id="progressLabel"></p>
  <p class="ai-status" id="aiStatus" role="status" hidden></p>
</header>
<nav class="tabs" role="tablist" id="navigation">
  <button class="tab" role="tab" id="tab-files" aria-controls="files" aria-selected="true"><span id="filesLabel"></span><span class="badge" id="filesCount"></span></button>
  <button class="tab" role="tab" id="tab-drafts" aria-controls="drafts" aria-selected="false" tabindex="-1"><span id="draftsLabel"></span><span class="badge" id="draftsCount"></span></button>
  <button class="tab" role="tab" id="tab-conversations" aria-controls="conversations" aria-selected="false" tabindex="-1"><span id="conversationsLabel"></span><span class="badge" id="conversationsCount"></span></button>
</nav>
<div class="filters">
  <input type="search" id="search">
  <select id="fileFilter"></select>
  <select id="profileFilter" hidden></select>
  <select id="severityFilter" hidden></select>
  <select id="threadFilter" hidden></select>
</div>
<main id="results">
  <section class="panel" id="files" role="tabpanel" aria-labelledby="tab-files" tabindex="0"><ul id="fileList"></ul></section>
  <section class="panel" id="drafts" role="tabpanel" aria-labelledby="tab-drafts" tabindex="0" hidden><p class="hint" id="draftHint"></p><ul id="draftsList"></ul></section>
  <section class="panel" id="conversations" role="tabpanel" aria-labelledby="tab-conversations" tabindex="0" hidden><ul id="threadsList"></ul></section>
  <p class="empty" id="empty" role="status" hidden></p>
</main>
<footer>
  <div class="actions">
    <button class="button secondary" data-command="reviewWithAI" id="reviewWithAI"><span class="icon" id="aiIcon">${ICONS.sparkle}</span><span id="reviewWithAILabel"></span></button>
    <button class="button secondary" id="cancelAiReview" hidden></button>
    <button class="button primary" data-command="publish"><span class="icon">${ICONS.send}</span><span id="publishLabel"></span><span class="count" id="draftCount"></span></button>
  </div>
</footer>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const ICONS = ${JSON.stringify(ICONS)};
  const defaults = { closed: [], selected: '', tab: 'files', search: '', fileFilter: 'all', profileFilter: 'all', severityFilter: 'all', threadFilter: 'active' };
  let state = { ...defaults, ...vscode.getState() };
  if (!['files', 'drafts', 'conversations'].includes(state.tab)) state.tab = 'files';
  let closed = new Set(state.closed);
  const save = () => vscode.setState({ ...state, closed: [...closed] });
  const $ = id => document.getElementById(id);
  let data;
  let labels = {};
  const tabs = ['files', 'drafts', 'conversations'];

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function icon(name) {
    const node = el('span', 'icon ' + name);
    node.innerHTML = ICONS[name];
    return node;
  }
  function remember(box, key) {
    box.open = !closed.has(key);
    box.addEventListener('toggle', () => {
      box.open ? closed.delete(key) : closed.add(key);
      save();
    });
  }
  function row(node, key, onOpen) {
    node.classList.add('row');
    node.tabIndex = 0;
    node.dataset.key = key;
    node.setAttribute('role', 'button');
    if (key === state.selected) node.classList.add('selected');
    const open = () => {
      document.querySelectorAll('.row.selected').forEach(r => r.classList.remove('selected'));
      node.classList.add('selected');
      state.selected = key;
      save();
      onOpen();
    };
    node.addEventListener('click', open);
    node.addEventListener('keydown', event => {
      if (node.tagName !== 'BUTTON' && event.target === node && (event.key === 'Enter' || event.key === ' ')) {
        event.preventDefault(); open();
      }
    });
    return node;
  }
  function badge(name, count, label) {
    const node = el('span', 'comments');
    node.title = label + ': ' + count;
    node.setAttribute('aria-label', node.title);
    node.append(icon(name), document.createTextNode(count));
    return node;
  }
  function matches(text) { return text.toLocaleLowerCase().includes(state.search.trim().toLocaleLowerCase()); }
  function files(entries, depth) {
    return entries.flatMap(e => {
      const li = el('li');
      if (e.children) {
        const children = files(e.children, depth + 1);
        if (!children.length) return [];
        const box = el('details');
        remember(box, 'folder:' + e.path);
        if (state.search.trim()) box.open = true;
        const summary = el('summary', 'row folder');
        summary.style.setProperty('--depth', depth);
        summary.append(icon('chevron'), el('span', 'name', e.folder));
        const list = el('ul'); list.append(...children);
        box.append(summary, list); li.append(box);
        return [li];
      }
      if (!matches(e.file) || (state.fileFilter === 'unviewed' && e.viewed) || (state.fileFilter === 'pending' && !e.drafts && !e.comments)) return [];
      const viewed = el('input');
      viewed.type = 'checkbox'; viewed.checked = e.viewed; viewed.title = labels.viewed;
      viewed.dataset.key = 'viewed:' + e.file;
      viewed.setAttribute('aria-label', labels.viewed + ': ' + e.file);
      viewed.addEventListener('click', event => event.stopPropagation());
      viewed.addEventListener('change', () => vscode.postMessage({ type: 'viewed', path: e.file, viewed: viewed.checked }));
      const node = row(el('button', e.viewed ? 'file viewed' : 'file'), 'file:' + e.file, () => vscode.postMessage({ type: 'openFile', path: e.file }));
      const container = el('div', 'file-row');
      container.style.setProperty('--depth', depth); node.title = e.file;
      node.append(el('span', 'name', e.name));
      if (e.drafts) node.append(badge('edit', e.drafts, labels.drafts));
      if (e.comments) node.append(badge('unresolved', e.comments, labels.active));
      node.append(el('span', 'status ' + e.status, e.status));
      container.append(viewed, node); li.append(container); return [li];
    });
  }
  function threads(list, resolved) {
    return list.filter(t => matches(t.place + ' ' + t.author + ' ' + t.text)).map(t => {
      const li = el('li');
      const node = row(el('div', 'thread'), 'thread:' + t.id, () => vscode.postMessage({ type: 'openThread', id: t.id }));
      const lines = el('div', 'lines');
      const meta = el('div', 'metadata');
      meta.append(el('span', '', t.author), el('span', '', resolved ? labels.resolved : labels.active));
      if (t.outdated) meta.append(el('span', 'tag', labels.outdated));
      lines.append(el('div', 'text', t.text), el('div', 'place', t.place), meta);
      node.title = t.place + ': ' + t.text;
      node.append(icon(resolved ? 'resolved' : 'unresolved'), lines); li.append(node); return li;
    });
  }
  function drafts(list) {
    return list.filter(d => matches(d.place + ' ' + d.who + ' ' + d.text)
      && (state.profileFilter === 'all' || (state.profileFilter === 'manual' ? d.kind === 'manual' : 'profile:' + d.who === state.profileFilter))
      && (state.severityFilter === 'all' || d.kind === state.severityFilter)).map(d => {
      const li = el('li');
      const node = row(el('div', 'thread'), 'draft:' + d.id, () => vscode.postMessage({ type: 'openDraft', id: d.id }));
      const lines = el('div', 'lines');
      const meta = el('div', 'metadata');
      meta.append(el('span', 'severity ' + d.kind, labels[d.kind]));
      if (d.kind !== 'manual') meta.append(el('span', '', labels.ai + ' · ' + d.who));
      lines.append(el('div', 'text', d.text), el('div', 'place', d.place), meta);
      node.title = d.place + ': ' + d.text;
      const mark = icon(d.kind === 'manual' ? 'edit' : 'sparkle'); mark.classList.add(d.kind);
      node.append(mark, lines); li.append(node); return li;
    });
  }
  function options(id, items, label) {
    const select = $(id);
    select.setAttribute('aria-label', label);
    select.title = label;
    select.replaceChildren(...items.map(([value, text]) => {
      const option = el('option', '', text); option.value = value; return option;
    }));
    if (!items.some(([value]) => value === state[id])) state[id] = items[0][0];
    select.value = state[id];
  }
  function renderLists() {
    if (!data) return;
    const focused = document.activeElement?.dataset.key;
    const lists = {
      files: files(data.files, 0),
      drafts: drafts(data.drafts),
      conversations: [
        ...(state.threadFilter !== 'resolved' ? threads(data.active, false) : []),
        ...(state.threadFilter !== 'active' ? threads(data.resolved, true) : [])
      ]
    };
    $('fileList').replaceChildren(...lists.files);
    $('draftsList').replaceChildren(...lists.drafts);
    $('threadsList').replaceChildren(...lists.conversations);
    $('empty').hidden = lists[state.tab].length !== 0;
    if (focused) {
      const target = document.querySelector('[data-key="' + CSS.escape(focused) + '"]');
      (target || $(state.tab)).focus({ preventScroll: true });
    }
  }
  function selectTab(tab, focus = false) {
    state.tab = tab;
    for (const id of tabs) {
      $(id).hidden = id !== tab;
      $('tab-' + id).setAttribute('aria-selected', String(id === tab));
      $('tab-' + id).tabIndex = id === tab ? 0 : -1;
    }
    $('fileFilter').hidden = tab !== 'files';
    $('profileFilter').hidden = $('severityFilter').hidden = tab !== 'drafts';
    $('threadFilter').hidden = tab !== 'conversations';
    if (focus) $('tab-' + tab).focus();
    renderLists(); save();
  }
  for (const tab of tabs) {
    $('tab-' + tab).addEventListener('click', () => selectTab(tab));
    $('tab-' + tab).addEventListener('keydown', e => {
      let index = tabs.indexOf(tab);
      if (e.key === 'ArrowRight') index++;
      else if (e.key === 'ArrowLeft') index--;
      else if (e.key === 'Home') index = 0;
      else if (e.key === 'End') index = tabs.length - 1;
      else return;
      e.preventDefault(); selectTab(tabs[(index + tabs.length) % tabs.length], true);
    });
  }
  for (const id of ['fileFilter', 'profileFilter', 'severityFilter', 'threadFilter']) {
    $(id).addEventListener('change', () => { state[id] = $(id).value; renderLists(); save(); });
  }
  $('search').value = state.search;
  $('search').addEventListener('input', () => { state.search = $('search').value; renderLists(); save(); });
  $('details').open = !!state.details;
  $('details').addEventListener('toggle', () => { state.details = $('details').open; save(); });
  $('cancelAiReview').addEventListener('click', () => vscode.postMessage({ type: 'cancelAiReview' }));
  // The extension cannot see its window resize: the page tells it, and it puts the page back to its width.
  let resizeTimer;
  window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(() => vscode.postMessage({ type: 'resized' }), 250); });
  window.addEventListener('message', ({ data: message }) => {
    if (message.type !== 'update') return;
    const d = data = message.data;
    if (state.pr !== undefined && state.pr !== d.number) {
      state = { ...defaults }; closed = new Set(); $('search').value = ''; $('details').open = false;
    }
    state.pr = d.number;
    labels = d.labels;
    $('number').textContent = '#' + d.number; $('title').textContent = d.title;
    $('heading').title = '#' + d.number + ' ' + d.title;
    $('author').textContent = d.author;
    $('description').textContent = d.description || labels.noDescription;
    $('detailsLabel').textContent = labels.details;
    $('openInBrowser').title = labels.openInBrowser;
    $('openInBrowser').setAttribute('aria-label', labels.openInBrowser);
    $('navigation').setAttribute('aria-label', labels.navigation);
    $('search').placeholder = labels.search; $('search').setAttribute('aria-label', labels.search);
    for (const tab of tabs) $('' + tab + 'Label').textContent = labels[tab];
    $('filesCount').textContent = d.total;
    $('draftsCount').textContent = d.drafts.length;
    $('conversationsCount').textContent = d.active.length;
    $('conversationsCount').title = labels.active + ': ' + d.active.length + ' · ' + labels.resolved + ': ' + d.resolved.length;
    $('publishLabel').textContent = labels.publish; $('draftCount').textContent = d.drafts.length;
    $('draftHint').textContent = labels.unpublished;
    $('empty').textContent = labels.empty;
    options('fileFilter', [['all', labels.allFiles], ['unviewed', labels.unviewed], ['pending', labels.withPending]], labels.fileFilter);
    options('profileFilter', [['all', labels.allOrigins], ['manual', labels.manual], ...[...new Set(d.drafts.filter(x => x.kind !== 'manual').map(x => x.who))].sort().map(x => ['profile:' + x, x])], labels.profile);
    options('severityFilter', [['all', labels.allSeverities], ...['error', 'warning', 'info', 'manual'].map(x => [x, labels[x]])], labels.severity);
    options('threadFilter', [['active', labels.active + ' (' + d.active.length + ')'], ['resolved', labels.resolved + ' (' + d.resolved.length + ')'], ['all', labels.allThreads]], labels.threadFilter);
    $('reviewWithAI').disabled = d.aiReview !== undefined;
    $('aiIcon').innerHTML = d.aiReview === undefined ? ICONS.sparkle : ICONS.spinner;
    $('reviewWithAILabel').textContent = labels.reviewWithAI;
    $('aiStatus').textContent = d.aiReview || ''; $('aiStatus').hidden = d.aiReview === undefined;
    $('cancelAiReview').hidden = d.aiReview === undefined; $('cancelAiReview').textContent = labels.cancel;
    $('progressFill').style.width = (d.total ? (d.viewed / d.total) * 100 : 0) + '%';
    $('progress').classList.toggle('done', d.total > 0 && d.viewed === d.total);
    $('progress').setAttribute('aria-valuemax', d.total); $('progress').setAttribute('aria-valuenow', d.viewed);
    $('progress').setAttribute('aria-label', labels.progress); $('progressLabel').textContent = labels.progress;
    selectTab(state.tab);
  });
  document.querySelectorAll('[data-command]').forEach(b => b.addEventListener('click', () => vscode.postMessage({ type: 'command', command: b.dataset.command })));
  vscode.postMessage({ type: 'ready' });
</script>
</body>
</html>`;
}
