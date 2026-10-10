import * as vscode from "vscode";
import type { Draft, Thread } from "../providers/types";
import { headPathOf, headUri, openFileDiff, SCHEME, threadLines, threadUri } from "./diff";
import type { ReviewSession } from "./session";

export class DraftComment implements vscode.Comment {
  mode = vscode.CommentMode.Preview;
  readonly author = { name: vscode.l10n.t("You") };
  readonly label: string;
  readonly contextValue = "draft";
  body: string | vscode.MarkdownString;
  parent!: vscode.CommentThread;

  constructor(readonly draft: Draft) {
    this.body = new vscode.MarkdownString(draft.body);
    const origin = draft.origin;
    this.label = origin === "manual" ? vscode.l10n.t("Draft") : vscode.l10n.t("AI draft · {0} · {1}", origin.profile, SEVERITIES[origin.severity]());
  }
}

const SEVERITIES = { error: () => vscode.l10n.t("error"), warning: () => vscode.l10n.t("warning"), info: () => vscode.l10n.t("info") };

/** A suggestion block, fenced with more backticks than any run in the code. */
export function suggestionBlock(code: string): string {
  const longest = Math.max(0, ...[...code.matchAll(/`+/g)].map((m) => m[0].length));
  const fence = "`".repeat(Math.max(3, longest + 1));
  return `${fence}suggestion\n${code}\n${fence}`;
}

/** A column past any line's end: the editor clamps it, so a range up to it marks the line whole. */
const LINE_END = Number.MAX_SAFE_INTEGER;

/**
 * Where a thread is marked in the editor: the text the reviewer selected, or its lines whole.
 * A single whole line gets an empty range: its widget marks it, as VS Code's own comments do.
 */
function threadRange(startLine: number, endLine: number, startColumn?: number, endColumn?: number): vscode.Range {
  if (startColumn !== undefined && endColumn !== undefined) {
    return new vscode.Range(startLine - 1, startColumn - 1, endLine - 1, endColumn - 1);
  }
  return new vscode.Range(startLine - 1, 0, endLine - 1, startLine === endLine ? 0 : LINE_END);
}

/** The selection as the editor shows it: one that ends at the start of a line stops at the end of the line before. */
function selectedRange(document: vscode.TextDocument, range: vscode.Range): vscode.Range {
  return range.end.character === 0 && range.end.line > range.start.line ? range.with({ end: document.lineAt(range.end.line - 1).range.end }) : range;
}

/** Shows existing threads and drafts in the editor gutter and turns new comments into drafts. */
export class CommentsManager implements vscode.Disposable {
  private readonly controller = vscode.comments.createCommentController("smartPrReview", "Smart PR Review");
  private readonly subscription: vscode.Disposable;
  private readonly threadIds = new WeakMap<vscode.CommentThread, string>();
  private threads: vscode.CommentThread[] = [];
  /** The pull request (and head, and checkout) the gutter "+" was computed for. */
  private rangesFor = "";
  private navigationTimer: ReturnType<typeof setTimeout> | undefined;
  private navigating = false;
  /** How each editor's current selection arrived: Comments reveals are Command/undefined, users use Mouse/Keyboard. */
  private readonly selectionKinds = new WeakMap<vscode.TextEditor, vscode.TextEditorSelectionChangeKind | undefined>();

  constructor(private readonly session: ReviewSession) {
    this.controller.commentingRangeProvider = { provideCommentingRanges: (document) => this.provideCommentingRanges(document) };
    const scheduleNavigation = () => {
      clearTimeout(this.navigationTimer);
      this.navigationTimer = setTimeout(() => {
        void this.reopenCommentInDiff().catch((error) => console.error("Smart PR Review: comment navigation failed", error));
      }, 50);
    };
    this.subscription = vscode.Disposable.from(
      session.onDidChange(() => this.render()),
      vscode.window.onDidChangeActiveTextEditor(scheduleNavigation),
      vscode.window.onDidChangeTextEditorSelection((e) => {
        this.selectionKinds.set(e.textEditor, e.kind);
        scheduleNavigation();
      }),
      vscode.window.tabGroups.onDidChangeTabs(scheduleNavigation),
    );
  }

  /** The native Comments panel opens a thread's URI, not our open-diff command.
   * Follow that programmatic reveal on a head-side document and reopen the same place in the pull request diff.
   * Mouse and keyboard moves are the user's own navigation and are never redirected.
   * Coalesce editor/tab/selection events because VS Code sends the selected range after opening the tab.
   */
  private async reopenCommentInDiff(): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    const tab = vscode.window.tabGroups.activeTabGroup.activeTab;
    const c = this.session.current;
    const uri = editor?.document.uri;
    if (
      this.navigating ||
      !editor ||
      !uri ||
      !c ||
      !(tab?.input instanceof vscode.TabInputText) ||
      tab.input.uri.toString() !== editor.document.uri.toString()
    ) {
      return;
    }
    const kind = this.selectionKinds.get(editor);
    if (kind === vscode.TextEditorSelectionChangeKind.Mouse || kind === vscode.TextEditorSelectionChangeKind.Keyboard) {
      return;
    }
    // A checked-out pull request's threads live on the user's editable working file: only an explicit
    // programmatic reveal (Command) may redirect it, never a bare open that happens to land on a comment.
    if (uri.scheme !== SCHEME && kind !== vscode.TextEditorSelectionChangeKind.Command) {
      return;
    }
    const path = headPathOf(this.session, uri);
    const file = c.files.find((f) => f.path === path);
    const selection = editor.selection;
    // Comments selects exactly the thread's range (clamped to its lines by the editor). Go to Definition, search
    // results or Outline also reveal programmatically (Command), but land on a symbol or a match: those must stay in the user's editor.
    if (!file || !this.threads.some((t) => t.uri.toString() === uri.toString() && editor.document.validateRange(t.range).isEqual(selection))) {
      return;
    }
    this.navigating = true;
    try {
      // The diff gets the range itself: reading the new editor back right after opening it races the editor list.
      await openFileDiff(this.session, file, selection, editor.viewColumn);
      // Only the transient read-only tab opened by Comments; leave pinned tabs alone.
      if (tab.isPreview && !tab.isDirty && vscode.window.tabGroups.all.some((g) => g.tabs.includes(tab))) {
        await vscode.window.tabGroups.close(tab, true);
      }
    } finally {
      this.navigating = false;
    }
  }

  async provideCommentingRanges(document: vscode.TextDocument): Promise<vscode.Range[]> {
    const path = headPathOf(this.session, document.uri);
    if (!path) {
      return [];
    }
    const ranges = await this.session.commentableRanges(path);
    if (ranges === "all") {
      return [new vscode.Range(0, 0, Math.max(document.lineCount - 1, 0), 0)];
    }
    return ranges.map((r) => new vscode.Range(r.start - 1, 0, r.end - 1, 0));
  }

  /** ponytail: re-creates every thread on each change; diff the set if flicker shows up on large PRs. */
  render(): void {
    this.threads.forEach((t) => t.dispose());
    this.threads = [];
    const c = this.session.current;
    const rangesFor = c ? `${c.pr.id}:${c.pr.headSha}:${c.checkedOut}` : "";
    if (rangesFor !== this.rangesFor) {
      this.rangesFor = rangesFor;
      // Setting the provider again makes VS Code ask open editors for their ranges (none after exiting).
      this.controller.commentingRangeProvider = { provideCommentingRanges: (document) => this.provideCommentingRanges(document) };
    }
    if (!c) {
      return;
    }
    for (const thread of c.threads) {
      const at = threadLines(thread);
      if (at) {
        this.threads.push(this.renderThread(thread, at));
      }
    }
    for (const draft of c.drafts.all()) {
      this.threads.push(this.renderDraft(draft));
    }
  }

  /** The new comment's thread has the reviewer's selection as its range: the draft keeps it, columns included. */
  async createDraft(reply: vscode.CommentReply): Promise<void> {
    const { uri, range } = reply.thread;
    const path = headPathOf(this.session, uri);
    reply.thread.dispose();
    if (path) {
      const selected = selectedRange(await vscode.workspace.openTextDocument(uri), range);
      const columns = selected.isEmpty ? undefined : { startColumn: selected.start.character + 1, endColumn: selected.end.character + 1 };
      await this.session.addDraft(path, selected.start.line + 1, selected.end.line + 1, reply.text, "manual", columns);
    }
  }

  /** "Add as Suggestion" on a new comment: its text plus the commented lines in a suggestion block, opened for editing. */
  async createSuggestion(reply: vscode.CommentReply): Promise<void> {
    const { uri, range } = reply.thread;
    const path = headPathOf(this.session, uri);
    reply.thread.dispose();
    if (path) {
      const document = await vscode.workspace.openTextDocument(uri);
      const { start, end } = selectedRange(document, range);
      await this.addSuggestionDraft(path, document, start.line, end.line, reply.text);
    }
  }

  async reply(reply: vscode.CommentReply): Promise<void> {
    const id = this.threadIds.get(reply.thread);
    if (id) {
      await this.session.reply(id, reply.text);
    }
  }

  async setResolved(thread: vscode.CommentThread, resolved: boolean): Promise<void> {
    const id = this.threadIds.get(thread);
    if (id) {
      await this.session.setResolved(id, resolved);
    }
  }

  editDraft(comment: DraftComment): void {
    comment.mode = vscode.CommentMode.Editing;
    comment.parent.comments = [...comment.parent.comments];
  }

  async saveDraft(comment: DraftComment): Promise<void> {
    const text = typeof comment.body === "string" ? comment.body : comment.body.value;
    await this.session.updateDraft(comment.draft.id, text);
  }

  cancelEditDraft(): void {
    this.render();
  }

  async deleteDraft(comment: DraftComment): Promise<void> {
    await this.session.removeDrafts([comment.draft.id]);
  }

  /** Creates a draft with a ```suggestion block holding the selected lines and opens it for editing. */
  async addSuggestion(editor: vscode.TextEditor | undefined): Promise<void> {
    const path = editor && headPathOf(this.session, editor.document.uri);
    if (!editor || !path) {
      void vscode.window.showWarningMessage(vscode.l10n.t("Open the head side of a pull request file to add a suggestion."));
      return;
    }
    const { start, end } = selectedRange(editor.document, editor.selection);
    const ranges = await this.session.commentableRanges(path);
    if (ranges !== "all" && !ranges.some((r) => start.line + 1 >= r.start && end.line + 1 <= r.end)) {
      void vscode.window.showWarningMessage(vscode.l10n.t("Suggestions can only be added to lines that are part of the pull request diff."));
      return;
    }
    await this.addSuggestionDraft(path, editor.document, start.line, end.line, "");
  }

  /** A draft on 0-based lines first..last with `text` and those lines as a suggestion, opened for editing. */
  private async addSuggestionDraft(path: string, document: vscode.TextDocument, first: number, last: number, text: string): Promise<void> {
    const code = document.getText(new vscode.Range(first, 0, last, document.lineAt(last).text.length));
    const draft = await this.session.addDraft(path, first + 1, last + 1, [text.trim(), suggestionBlock(code)].filter(Boolean).join("\n\n"));
    const comment = this.threads.flatMap((t) => t.comments).find((x): x is DraftComment => x instanceof DraftComment && x.draft.id === draft.id);
    if (comment) {
      this.editDraft(comment);
    }
  }

  dispose(): void {
    clearTimeout(this.navigationTimer);
    this.subscription.dispose();
    // Disposing the controller removes its threads; disposing them first would delete each one twice.
    this.controller.dispose();
  }

  private renderThread(t: Thread, at: { line: number; startLine?: number }): vscode.CommentThread {
    const c = this.session.require();
    const uri = threadUri(c, this.session.repoRoot, t);
    const comments = t.comments.map(
      (x): vscode.Comment => ({
        body: new vscode.MarkdownString(x.body),
        mode: vscode.CommentMode.Preview,
        author: { name: x.author },
        timestamp: new Date(x.createdAt),
        contextValue: "existing",
      }),
    );
    const thread = this.controller.createCommentThread(uri, threadRange(at.startLine ?? at.line, at.line), comments);
    thread.canReply = true;
    thread.contextValue = t.isResolved ? "existing-resolved" : "existing-unresolved";
    thread.label = t.isOutdated ? vscode.l10n.t("Outdated") : t.isResolved ? vscode.l10n.t("Resolved") : vscode.l10n.t("Active");
    thread.state = t.isResolved ? vscode.CommentThreadState.Resolved : vscode.CommentThreadState.Unresolved;
    thread.collapsibleState = t.isResolved ? vscode.CommentThreadCollapsibleState.Collapsed : vscode.CommentThreadCollapsibleState.Expanded;
    this.threadIds.set(thread, t.id);
    return thread;
  }

  private renderDraft(draft: Draft): vscode.CommentThread {
    const c = this.session.require();
    const comment = new DraftComment(draft);
    const uri = headUri(this.session.repoRoot, draft.path, c.pr.headSha, c.checkedOut);
    const thread = this.controller.createCommentThread(uri, threadRange(draft.startLine, draft.endLine, draft.startColumn, draft.endColumn), [
      comment,
    ]);
    comment.parent = thread;
    thread.canReply = false;
    thread.contextValue = "draft";
    thread.label = vscode.l10n.t("Draft");
    thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
    return thread;
  }
}
