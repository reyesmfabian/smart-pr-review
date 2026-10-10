import * as assert from "node:assert/strict";
import * as vscode from "vscode";
import { CommentsManager, suggestionBlock } from "../review/comments";
import { closeReviewTabs, openThread, RevisionContentProvider, revisionUri, SCHEME } from "../review/diff";
import { ReviewSession } from "../review/session";
import { FakeProvider, memento } from "./fakes";
import { makeRepo, type TestRepo } from "./repo";

suite("CommentsManager", () => {
  let repo: TestRepo;
  let session: ReviewSession;
  let manager: CommentsManager;
  let registration: vscode.Disposable;

  setup(async () => {
    repo = await makeRepo();
    session = new ReviewSession(new FakeProvider(repo), repo.root, "origin", memento(), "github:octo/app");
    await session.open(1);
    registration = vscode.workspace.registerTextDocumentContentProvider(SCHEME, new RevisionContentProvider(repo.root));
    manager = new CommentsManager(session);
  });

  teardown(async () => {
    await vscode.commands.executeCommand("workbench.action.closeAllEditors");
    manager.dispose();
    registration.dispose();
    session.dispose();
  });

  test("labels existing threads Active or Resolved, and collapses the resolved ones", async () => {
    const thread = { path: "src/app.ts", side: "head" as const, isOutdated: false, comments: [{ id: "C", author: "ana", body: "x", createdAt: "" }] };
    (session.provider as FakeProvider).threads = [
      { ...thread, id: "A", line: 5, isResolved: false },
      { ...thread, id: "R", line: 6, isResolved: true },
      // Outdated: shown where it was written (an older commit of the pull request), so it stays in the Comments panel.
      { ...thread, id: "O", isOutdated: true, isResolved: false, original: { sha: repo.baseSha, line: 3, startLine: 2 } },
      // Outdated without a known place (Azure DevOps): nowhere to show it.
      { ...thread, id: "X", isOutdated: true, isResolved: false },
    ];
    await session.open(1);
    const rendered = (manager as unknown as { threads: vscode.CommentThread[] }).threads;
    assert.deepStrictEqual(
      rendered.map((t) => [t.label, t.state, t.collapsibleState]),
      [
        ["Active", vscode.CommentThreadState.Unresolved, vscode.CommentThreadCollapsibleState.Expanded],
        ["Resolved", vscode.CommentThreadState.Resolved, vscode.CommentThreadCollapsibleState.Collapsed],
        ["Outdated", vscode.CommentThreadState.Unresolved, vscode.CommentThreadCollapsibleState.Expanded],
      ],
    );
    assert.strictEqual(rendered[2].uri.toString(), revisionUri("src/app.ts", repo.baseSha).toString());
    assert.deepStrictEqual([rendered[2].range.start.line, rendered[2].range.end.line], [1, 2]);
  });

  test('"Go to Comment" opens the diff at the thread, or the base version for a base-side one', async () => {
    const thread = { id: "T", path: "src/app.ts", side: "head" as const, line: 6, isOutdated: false, isResolved: false, comments: [] };
    await openThread(session, thread);
    let editor = vscode.window.activeTextEditor!;
    assert.deepStrictEqual([editor.document.uri.toString(), editor.selection.active.line], [revisionUri("src/app.ts", repo.headSha).toString(), 5]);
    await openThread(session, { ...thread, side: "base", line: 2 });
    editor = vscode.window.activeTextEditor!;
    assert.deepStrictEqual(
      [editor.document.uri.toString(), editor.selection.active.line],
      [revisionUri("src/app.ts", session.require().mergeBase).toString(), 1],
    );
    // An outdated thread opens the version it was written against, at its line there.
    await openThread(session, { ...thread, isOutdated: true, line: undefined, original: { sha: repo.baseSha, line: 3 } });
    editor = vscode.window.activeTextEditor!;
    assert.deepStrictEqual([editor.document.uri.toString(), editor.selection.active.line], [revisionUri("src/app.ts", repo.baseSha).toString(), 2]);
  });

  test("offers comments only on diff lines of the head side", async () => {
    const head = await vscode.workspace.openTextDocument(revisionUri("src/app.ts", repo.headSha));
    assert.deepStrictEqual(
      (await manager.provideCommentingRanges(head)).map((r) => [r.start.line, r.end.line]),
      [[1, 7]],
    );
    const base = await vscode.workspace.openTextDocument(revisionUri("src/app.ts", repo.baseSha));
    assert.deepStrictEqual(await manager.provideCommentingRanges(base), []);
  });

  /**
   * The active diff and its head-side editor once `selected` holds for it: the extension host gets the tab first,
   * then the editor, then its selection.
   */
  async function waitForDiff(
    selected: (selection: vscode.Selection) => boolean,
  ): Promise<{ input: vscode.TabInputTextDiff; modified: vscode.TextEditor }> {
    for (let i = 0; i < 100; i++) {
      const input = vscode.window.tabGroups.activeTabGroup.activeTab?.input;
      const modified =
        input instanceof vscode.TabInputTextDiff
          ? vscode.window.visibleTextEditors.find((e) => e.document.uri.toString() === input.modified.toString())
          : undefined;
      if (modified && selected(modified.selection)) {
        return { input: input as vscode.TabInputTextDiff, modified };
      }
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
    assert.fail("Comments navigation did not reopen the diff");
  }

  const rendered = () => (manager as unknown as { threads: vscode.CommentThread[] }).threads;

  test("native Comments resource navigation reopens a draft in the diff with its full range", async () => {
    await session.addDraft("src/app.ts", 4, 6, "Review these lines");
    const uri = revisionUri("src/app.ts", repo.headSha);
    // A draft on whole lines is marked to the end of its last line, a column the editor clamps to the line.
    const range = (await vscode.workspace.openTextDocument(uri)).validateRange(rendered()[0].range);
    assert.ok(range.isEqual(new vscode.Range(3, 0, 5, 6)), `rendered ${JSON.stringify(range)}`);
    // Native Comments calls openEditor(resource, { selection: thread.range }), not our command.
    await vscode.window.showTextDocument(uri, { selection: rendered()[0].range, preview: true });
    const { input } = await waitForDiff((selection) => selection.isEqual(range));
    assert.equal(input.original.toString(), revisionUri("src/app.ts", repo.baseSha).toString());
    assert.equal(input.modified.toString(), uri.toString());
    await new Promise((resolve) => setTimeout(resolve, 150));
    assert.ok(vscode.window.tabGroups.activeTabGroup.activeTab?.input instanceof vscode.TabInputTextDiff);
  });

  test("native Comments navigation switches from another file to the commented diff", async () => {
    (session.provider as FakeProvider).threads = [
      {
        id: "T",
        path: "src/app.ts",
        side: "head",
        line: 5,
        isOutdated: false,
        isResolved: false,
        comments: [{ id: "C", author: "ana", body: "x", createdAt: "" }],
      },
    ];
    await session.open(1);
    await vscode.commands.executeCommand("vscode.diff", revisionUri("src/new file.ts", repo.baseSha), revisionUri("src/new file.ts", repo.headSha));
    await vscode.window.showTextDocument(revisionUri("src/app.ts", repo.headSha), { selection: new vscode.Range(4, 0, 4, 0) });
    const { input } = await waitForDiff((selection) => selection.start.line === 4);
    assert.equal(input.modified.path, "/src/app.ts");
  });

  test("does not redirect a revision without a comment at the selection, or a symbol revealed on a commented line", async () => {
    await session.addDraft("src/app.ts", 5, 5, "Comment");
    await vscode.window.showTextDocument(revisionUri("src/app.ts", repo.headSha), { selection: new vscode.Range(8, 0, 8, 0) });
    await new Promise((resolve) => setTimeout(resolve, 200));
    assert.ok(vscode.window.tabGroups.activeTabGroup.activeTab?.input instanceof vscode.TabInputText);
    // Go to Definition, search and Outline also reveal programmatically, but select a symbol, not the thread's range.
    await vscode.window.showTextDocument(revisionUri("src/app.ts", repo.headSha), { selection: new vscode.Range(4, 2, 4, 5) });
    await new Promise((resolve) => setTimeout(resolve, 200));
    assert.ok(vscode.window.tabGroups.activeTabGroup.activeTab?.input instanceof vscode.TabInputText);
  });

  test("turns a new comment into a draft and drops the temporary thread", async () => {
    // The thread VS Code creates when the user clicks "+": createDraft only reads its position and disposes it.
    let disposed = false;
    const thread = {
      uri: revisionUri("src/app.ts", repo.headSha),
      range: new vscode.Range(4, 0, 4, 0),
      dispose: () => {
        disposed = true;
      },
    } as unknown as vscode.CommentThread;
    await manager.createDraft({ thread, text: "Rename this" });
    const drafts = session.require().drafts.all();
    assert.deepStrictEqual(
      drafts.map((d) => [d.path, d.startLine, d.endLine, d.startColumn, d.body, d.headSha]),
      [["src/app.ts", 5, 5, undefined, "Rename this", repo.headSha]],
    );
    assert.ok(disposed);
  });

  const places = () =>
    session
      .require()
      .drafts.all()
      .map((d) => [d.startLine, d.endLine, d.startColumn, d.endColumn]);

  test("a comment on part of a line marks just that part", async () => {
    await manager.createDraft({ thread: newThread(new vscode.Range(4, 8, 4, 12)), text: "Rename" });
    assert.deepStrictEqual(places(), [[5, 5, 9, 13]]);
    assert.ok(rendered()[0].range.isEqual(new vscode.Range(4, 8, 4, 12)));
  });

  test("a comment on selected lines covers all of them, with the selected columns", async () => {
    await manager.createDraft({ thread: newThread(new vscode.Range(3, 2, 5, 4)), text: "These three lines" });
    assert.deepStrictEqual(places(), [[4, 6, 3, 5]]);
    assert.ok(rendered()[0].range.isEqual(new vscode.Range(3, 2, 5, 4)));
  });

  test("a selection ending at the start of a line stops at the line before, as the editor shows it", async () => {
    await manager.createDraft({ thread: newThread(new vscode.Range(3, 0, 5, 0)), text: "Two lines" });
    assert.deepStrictEqual(places(), [[4, 5, 1, 15]]);
  });

  test("adds a suggestion prefilled with the selected lines", async () => {
    const editor = await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(revisionUri("src/app.ts", repo.headSha)));
    editor.selection = new vscode.Selection(4, 0, 4, 3);
    await manager.addSuggestion(editor);
    assert.strictEqual(session.require().drafts.all()[0].body, "```suggestion\nchanged line 5\n```");
  });

  test("rejects suggestions outside the diff", async () => {
    const editor = await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(revisionUri("src/app.ts", repo.headSha)));
    editor.selection = new vscode.Selection(9, 0, 9, 0);
    await manager.addSuggestion(editor);
    assert.deepStrictEqual(session.require().drafts.all(), []);
  });

  /** The thread VS Code creates when the user selects text and clicks "+": it has the selection as its range. */
  function newThread(range: vscode.Range): vscode.CommentThread {
    return { uri: revisionUri("src/app.ts", repo.headSha), range, dispose: () => undefined } as unknown as vscode.CommentThread;
  }

  test("a new comment can be added as a suggestion: its text plus the lines to change", async () => {
    await manager.createSuggestion({ thread: newThread(new vscode.Range(4, 0, 4, 0)), text: "Use a constant" });
    await manager.createSuggestion({ thread: newThread(new vscode.Range(3, 0, 5, 0)), text: "  " });
    assert.deepStrictEqual(
      session
        .require()
        .drafts.all()
        .map((d) => [d.startLine, d.endLine, d.body]),
      [
        [5, 5, "Use a constant\n\n```suggestion\nchanged line 5\n```"],
        [4, 5, "```suggestion\nline 4\nchanged line 5\n```"],
      ],
    );
  });

  test("a suggestion fence is longer than any backtick run in the code", () => {
    assert.strictEqual(suggestionBlock('const md = "```";'), '````suggestion\nconst md = "```";\n````');
  });

  test("exiting the review removes the comment gutter and closes the pull request diffs", async () => {
    await vscode.commands.executeCommand("vscode.diff", revisionUri("src/app.ts", repo.baseSha), revisionUri("src/app.ts", repo.headSha), "app.ts");
    const reviewTabs = () =>
      vscode.window.tabGroups.all
        .flatMap((g) => g.tabs)
        .filter((t) => t.input instanceof vscode.TabInputTextDiff && t.input.modified.scheme === SCHEME);
    assert.strictEqual(reviewTabs().length, 1);
    session.close();
    await closeReviewTabs();
    assert.deepStrictEqual(reviewTabs(), []);
    const head = await vscode.workspace.openTextDocument(revisionUri("src/app.ts", repo.headSha));
    assert.deepStrictEqual(await manager.provideCommentingRanges(head), []);
  });
});
