import * as assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { join } from "node:path";
import * as vscode from "vscode";
import { HttpError } from "../http";
import { RevisionContentProvider, headPathOf, revisionUri, threadUri } from "../review/diff";
import { CurrentPullRequestProvider, PullRequestListProvider, browserUri, fileTree, searchItems } from "../review/prTree";
import { ReviewSession } from "../review/session";
import { FakeProvider, memento } from "./fakes";
import { makeRepo, type TestRepo } from "./repo";

suite("diff documents and trees", () => {
  let repo: TestRepo;
  let provider: FakeProvider;
  let session: ReviewSession;

  setup(async () => {
    repo = await makeRepo();
    provider = new FakeProvider(repo);
    provider.viewed.add("src/app.ts");
    provider.threads = [
      {
        id: "T1",
        path: "src/app.ts",
        side: "head",
        isOutdated: true,
        isResolved: false,
        comments: [{ id: "C1", author: "ana", body: "old", createdAt: "" }],
      },
    ];
    session = new ReviewSession(provider, repo.root, "origin", memento(), "github:octo/app");
    await session.open(1);
  });

  teardown(() => session.dispose());

  test("serves file revisions, empty for files missing at that commit", async () => {
    const content = new RevisionContentProvider(repo.root);
    assert.match(await content.provideTextDocumentContent(revisionUri("src/app.ts", repo.headSha)), /changed line 5/);
    assert.strictEqual(await content.provideTextDocumentContent(revisionUri("src/new file.ts", repo.baseSha)), "");
  });

  test("refuses revision URIs whose sha is not a commit id (no git option injection)", async () => {
    const target = join(repo.root, "evil");
    const content = new RevisionContentProvider(repo.root);
    // Before validation this ran "git show --output=<target>:x", writing the file "evil:x".
    assert.strictEqual(await content.provideTextDocumentContent(revisionUri("x", `--output=${target}`)), "");
    assert.strictEqual(existsSync(`${target}:x`), false);
  });

  test("maps head-side documents back to pull request paths", () => {
    assert.strictEqual(headPathOf(session, revisionUri("src/new file.ts", repo.headSha)), "src/new file.ts");
    assert.strictEqual(headPathOf(session, revisionUri("src/app.ts", repo.baseSha)), undefined);
    assert.strictEqual(headPathOf(session, revisionUri("keep.txt", repo.headSha)), undefined);
    assert.strictEqual(headPathOf(session, vscode.Uri.file(join(repo.root, "src/app.ts"))), undefined);
    session.require().checkedOut = true;
    assert.strictEqual(headPathOf(session, vscode.Uri.file(join(repo.root, "src/app.ts"))), "src/app.ts");
  });

  test("puts base-side threads of a renamed file on the old path, where the diff shows it", () => {
    const c = session.require();
    const thread = { id: "T", path: "docs/new name.md", side: "base" as const, line: 1, isOutdated: false, isResolved: false, comments: [] };
    assert.strictEqual(threadUri(c, repo.root, thread).toString(), revisionUri("docs/old name.md", c.mergeBase).toString());
    assert.strictEqual(threadUri(c, repo.root, { ...thread, side: "head" }).toString(), revisionUri("docs/new name.md", c.pr.headSha).toString());
  });

  test("lists changed files in their folders with viewed state, then active and resolved comments apart", async () => {
    provider.threads.push(
      {
        id: "T2",
        path: "src/app.ts",
        side: "head",
        line: 5,
        isOutdated: false,
        isResolved: true,
        comments: [{ id: "C2", author: "luis", body: "done", createdAt: "" }],
      },
      {
        id: "T3",
        path: "src/app.ts",
        side: "head",
        line: 3,
        isOutdated: false,
        isResolved: false,
        comments: [{ id: "C3", author: "ana", body: "why?", createdAt: "" }],
      },
    );
    await session.open(1);
    const tree = new CurrentPullRequestProvider(session);
    const label = (n: ReturnType<typeof tree.getChildren>[number]) => {
      const item = tree.getTreeItem(n);
      return String(item.label ?? item.resourceUri?.path.split("/").pop());
    };
    const roots = tree.getChildren();
    assert.deepStrictEqual(roots.map(label), ["Files", "Conversations"]);
    const nodes = [...tree.getChildren(roots[0]), ...tree.getChildren(roots[1])];
    assert.deepStrictEqual(nodes.map(label), ["docs", "src", "keep.txt", "Active comments (2)", "Resolved comments (1)"]);
    const src = nodes[1];
    assert.deepStrictEqual(tree.getChildren(src).map(label), ["app.ts", "new file.ts"]);
    const app = tree.getChildren(src)[0];
    assert.strictEqual(tree.getTreeItem(app).checkboxState, vscode.TreeItemCheckboxState.Checked);
    assert.strictEqual(tree.getTreeItem(app).description, "M");

    const [active, resolved] = nodes.slice(3).map((n) => tree.getTreeItem(n));
    assert.strictEqual(active.collapsibleState, vscode.TreeItemCollapsibleState.Expanded);
    assert.strictEqual(resolved.collapsibleState, vscode.TreeItemCollapsibleState.Collapsed);
    // Active ones by position; the outdated one says so, since it has no line to go to.
    const activeThreads = tree.getChildren(nodes[3]).map((n) => tree.getTreeItem(n));
    assert.deepStrictEqual(
      activeThreads.map((i) => [i.label, i.description]),
      [
        ["src/app.ts", "outdated · ana: old"],
        ["src/app.ts:3", "ana: why?"],
      ],
    );
    assert.deepStrictEqual(activeThreads[1].command?.command, "smartPrReview.openThread");
    assert.deepStrictEqual(
      tree.getChildren(nodes[4]).map((n) => (tree.getTreeItem(n).iconPath as vscode.ThemeIcon).id),
      ["pass"],
    );
  });

  test("lists the drafts as they are written, between the files and the comments", async () => {
    await session.addDraft("src/app.ts", 6, 7, "Mine\n\nmore");
    await session.addDraft("src/app.ts", 5, 5, "Null check missing", { profile: "Security", engine: "claude", severity: "error" });
    const tree = new CurrentPullRequestProvider(session);
    const nodes = tree.getChildren();
    const drafts = nodes.find((n) => n.kind === "drafts")!;
    assert.strictEqual(nodes.indexOf(drafts), nodes.findIndex((n) => n.kind === "conversations") - 1);
    assert.strictEqual(tree.getTreeItem(drafts).label, "Drafts (2)");
    const items = tree.getChildren(drafts).map((n) => tree.getTreeItem(n));
    assert.deepStrictEqual(
      items.map((i) => [i.label, i.description]),
      [
        ["Null check missing", "Error · Security · src/app.ts:5"],
        ["Mine", "Manual · src/app.ts:6-7"],
      ],
    );
    assert.deepStrictEqual(items[0].command?.arguments?.[1], 5);
    assert.strictEqual((items[0].iconPath as vscode.ThemeIcon).id, "sparkle");
    assert.strictEqual((items[1].iconPath as vscode.ThemeIcon).id, "edit");
  });

  test("compacts folders that only hold one folder, as the Explorer does", () => {
    const tree = fileTree([
      { status: "M", path: "src/ai/review.ts" },
      { status: "M", path: "src/ai/engines.ts" },
      { status: "A", path: "packages/web/src/app.tsx" },
      { status: "M", path: "README.md" },
    ]);
    const shape = (nodes: ReturnType<typeof fileTree>): unknown[] =>
      nodes.map((n) => (n.kind === "folder" ? { [n.label]: shape(n.children) } : n.kind === "file" ? n.file.path : n.kind));
    assert.deepStrictEqual(shape(tree), [
      { "packages/web/src": ["packages/web/src/app.tsx"] },
      { "src/ai": ["src/ai/engines.ts", "src/ai/review.ts"] },
      "README.md",
    ]);
  });

  test("shows no pull request groups while signed out", async () => {
    assert.deepStrictEqual(
      await new PullRequestListProvider(
        provider,
        async () => false,
        () => assert.fail("unexpected list error"),
      ).getChildren(),
      [],
    );
    const groups = await new PullRequestListProvider(
      provider,
      async () => true,
      () => assert.fail("unexpected list error"),
    ).getChildren();
    assert.strictEqual(groups.length, 3);
    assert.strictEqual(
      (
        await new PullRequestListProvider(
          provider,
          async () => true,
          () => assert.fail("unexpected list error"),
        ).getChildren(groups[0])
      ).length,
      1,
    );
  });

  test("hands pull request list errors to the caller, so a 401 can offer to sign in again", async () => {
    provider.listPullRequests = async () => {
      throw new HttpError(401, "Bad credentials");
    };
    const errors: unknown[] = [];
    const list = new PullRequestListProvider(
      provider,
      async () => true,
      (error) => errors.push(error),
    );
    const [group] = await list.getChildren();
    assert.deepStrictEqual(await list.getChildren(group), []);
    assert.ok(errors[0] instanceof HttpError && errors[0].status === 401);
  });

  test("the pull request search lists the open ones and offers any number typed, even one not listed", () => {
    const prs = [12, 123].map((id) => ({ id, title: `Change ${id}`, author: "ana", updatedAt: "", url: "" }));
    assert.deepStrictEqual(
      searchItems(prs, "").map((i) => [i.id, i.label, i.description]),
      [
        [12, "#12 Change 12", "ana"],
        [123, "#123 Change 123", "ana"],
      ],
    );
    // Listed already: the quick pick's own filter finds it.
    assert.deepStrictEqual(
      searchItems(prs, " #12 ").map((i) => i.id),
      [12, 123],
    );
    const [first] = searchItems(prs, "#4567");
    assert.deepStrictEqual([first.id, first.label, first.alwaysShow], [4567, "$(arrow-right) Open pull request #4567", true]);
    assert.strictEqual(searchItems(prs, "login 12").length, 2);
  });

  test("pull requests in the list can be opened in the browser, and only http(s) addresses open", async () => {
    const list = new PullRequestListProvider(
      provider,
      async () => true,
      () => assert.fail("unexpected list error"),
    );
    const [group] = await list.getChildren();
    const [pr] = await list.getChildren(group);
    assert.strictEqual(list.getTreeItem(pr).contextValue, "pullRequest");
    assert.strictEqual(
      browserUri("https://dev.azure.com/org/proj/_git/repo/pullrequest/7")?.toString(),
      "https://dev.azure.com/org/proj/_git/repo/pullrequest/7",
    );
    assert.strictEqual(browserUri("javascript:alert(1)"), undefined);
    assert.strictEqual(browserUri("file:///etc/passwd"), undefined);
  });
});
