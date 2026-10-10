import * as assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { git, headSha } from "../git";
import { ReviewSession } from "../review/session";
import { FakeProvider, memento } from "./fakes";
import { makeRepo, type TestRepo } from "./repo";

suite("ReviewSession", () => {
  let repo: TestRepo;
  let provider: FakeProvider;
  let session: ReviewSession;

  setup(async () => {
    repo = await makeRepo();
    provider = new FakeProvider(repo);
    session = new ReviewSession(provider, repo.root, "origin", memento(), "github:octo/app");
    await session.open(1);
  });

  teardown(() => session.dispose());

  test("opens a pull request from its remote refs", () => {
    const c = session.require();
    assert.strictEqual(c.mergeBase, repo.baseSha);
    assert.deepStrictEqual(c.files.map((f) => f.path).sort(), ["docs/new name.md", "keep.txt", "src/app.ts", "src/new file.ts"]);
    assert.strictEqual(c.checkedOut, false);
  });

  test("keeps the pull request clicked last when two opens overlap", async () => {
    const getPullRequest = provider.getPullRequest.bind(provider);
    provider.getPullRequest = async (id) => {
      await new Promise((resolve) => setTimeout(resolve, id === 7 ? 300 : 0));
      return getPullRequest(id);
    };
    await Promise.all([session.open(7), session.open(8)]);
    assert.strictEqual(session.require().pr.id, 8);
  });

  test("fetches the head from another repository when the provider says so (Azure DevOps forks)", async () => {
    const fork = await makeRepo();
    const other = new FakeProvider(fork);
    const getPullRequest = other.getPullRequest.bind(other);
    other.getPullRequest = async (id) => ({
      ...(await getPullRequest(id)),
      fetchRefspecs: ["+refs/heads/main:refs/smart-pr-review/1/base"],
      headFetch: { url: pathToFileURL(fork.remote).href, refspec: "+refs/pull/1/head:refs/smart-pr-review/1/head" },
    });
    const forkSession = new ReviewSession(other, fork.root, "origin", memento(), "azureDevOps:x");
    await forkSession.open(1);
    assert.strictEqual(forkSession.require().files.length, 4);
    forkSession.dispose();
  });

  test("refuses a head fetch URL that is not http(s), ssh or file (it becomes a git argument)", async () => {
    const getPullRequest = provider.getPullRequest.bind(provider);
    provider.getPullRequest = async (id) => ({ ...(await getPullRequest(id)), headFetch: { url: "--upload-pack=touch /tmp/pwned", refspec: "x" } });
    await assert.rejects(session.open(1), /Unsupported repository URL/);
  });

  test("limits commentable lines to the diff when the provider requires it", async () => {
    assert.deepStrictEqual(await session.commentableRanges("src/app.ts"), [{ start: 2, end: 8 }]);
    assert.deepStrictEqual(await session.commentableRanges("keep.txt"), []);
    provider.commentableLines = "file";
    assert.strictEqual(await session.commentableRanges("src/app.ts"), "all");
  });

  test("marks files as viewed", async () => {
    await session.setViewed("src/app.ts", true);
    assert.ok(provider.viewed.has("src/app.ts"));
    assert.ok(session.require().viewed.has("src/app.ts"));
  });

  test("redraws the viewed checkbox when saving it fails, so it shows the real state", async () => {
    provider.setFileViewed = async () => {
      throw new Error("offline");
    };
    let redraws = 0;
    const subscription = session.onDidChange(() => redraws++);
    await assert.rejects(session.setViewed("src/app.ts", true), /offline/);
    subscription.dispose();
    assert.strictEqual(redraws, 1);
    assert.strictEqual(session.require().viewed.has("src/app.ts"), false);
  });

  test("publishes drafts and clears them", async () => {
    await session.addDraft("src/app.ts", 5, 5, "Nit");
    const result = await session.publish("Looks good", "approve", async () => assert.fail("no stale drafts expected"));
    assert.deepStrictEqual(result?.failed, []);
    assert.strictEqual(provider.published[0].drafts[0].body, "Nit");
    assert.strictEqual(provider.published[0].vote, "approve");
    assert.deepStrictEqual(session.require().drafts.all(), []);
    assert.strictEqual(session.require().drafts.summary(), "");
  });

  test("asks before publishing drafts written against an older head", async () => {
    await session.addDraft("src/app.ts", 5, 5, "Nit");
    provider.headSha = "f".repeat(40);
    let asked = 0;
    const result = await session.publish("", "comment", async (count) => {
      asked = count;
      return false;
    });
    assert.strictEqual(result, undefined);
    assert.strictEqual(asked, 1);
    assert.strictEqual(provider.published.length, 0);
    assert.strictEqual(session.require().drafts.all().length, 1);
  });

  test("keeps drafts that failed to publish, and the summary", async () => {
    await session.addDraft("src/app.ts", 5, 5, "ok");
    const bad = await session.addDraft("src/app.ts", 6, 6, "bad");
    provider.failing.add(bad.id);
    const result = await session.publish("Summary", "comment", async () => true);
    assert.deepStrictEqual(
      result?.failed.map((f) => f.id),
      [bad.id],
    );
    assert.deepStrictEqual(
      session
        .require()
        .drafts.all()
        .map((d) => d.id),
      [bad.id],
    );
    assert.strictEqual(session.require().drafts.summary(), "Summary");
  });

  test("keeps every draft when the provider rejects the whole review", async () => {
    await session.addDraft("src/app.ts", 5, 5, "Nit");
    provider.publishError = new Error("Can not approve your own pull request");
    await assert.rejects(
      session.publish("LGTM", "approve", async () => true),
      /own pull request/,
    );
    assert.strictEqual(session.require().drafts.all().length, 1);
    assert.strictEqual(session.require().drafts.summary(), "LGTM");
  });

  test("refuses to check out over uncommitted changes", async () => {
    await writeFile(join(repo.root, "keep.txt"), "dirty\n");
    await assert.rejects(
      session.checkout(async () => true),
      /uncommitted changes/,
    );
  });

  test("checks out the pull request head", async () => {
    await session.checkout(async () => assert.fail("a new branch needs no reset"));
    assert.strictEqual(await headSha(repo.root), repo.headSha);
    assert.strictEqual(session.require().checkedOut, true);
  });

  test("checks out the branch of a same-repository pull request under its name, tracking it on the remote", async () => {
    // The branch the pull request comes from, on the remote (the open above fetched its commit).
    await git(repo.root, ["push", "--quiet", "origin", `${repo.headSha}:refs/heads/feature`]);
    provider.headBranch = "feature";
    await session.open(1);
    await session.checkout(async () => assert.fail("a new branch needs no reset"));
    assert.strictEqual((await git(repo.root, ["branch", "--show-current"])).trim(), "feature");
    assert.strictEqual((await git(repo.root, ["rev-parse", "--abbrev-ref", "feature@{upstream}"])).trim(), "origin/feature");
    // What the reviewer does next to work on the branch: it needs the tracking information.
    await git(repo.root, ["pull", "--ff-only", "--quiet"]);
    assert.strictEqual(await headSha(repo.root), repo.headSha);
  });

  /** Leaves pr/1 with a commit the pull request does not have, as after the author force-pushes. */
  async function divergePullRequestBranch(): Promise<void> {
    await session.checkout(async () => true);
    await writeFile(join(repo.root, "local.txt"), "only on the local branch\n");
    await git(repo.root, ["add", "local.txt"]);
    await git(repo.root, ["commit", "--quiet", "-m", "local"]);
    await git(repo.root, ["checkout", "--quiet", "main"]);
  }

  test("resets a diverged pull request branch when the user confirms", async () => {
    await divergePullRequestBranch();
    let asked = "";
    await session.checkout(async (branch) => {
      asked = branch;
      return true;
    });
    assert.strictEqual(asked, "pr/1");
    assert.strictEqual(await headSha(repo.root), repo.headSha);
    assert.strictEqual((await git(repo.root, ["branch", "--show-current"])).trim(), "pr/1");
  });

  test("leaves a diverged pull request branch alone when the reset is declined", async () => {
    await divergePullRequestBranch();
    const localTip = (await git(repo.root, ["rev-parse", "pr/1"])).trim();
    await session.checkout(async () => false);
    assert.strictEqual((await git(repo.root, ["branch", "--show-current"])).trim(), "main");
    assert.strictEqual((await git(repo.root, ["rev-parse", "pr/1"])).trim(), localTip);
  });

  test("exiting the review forgets the pull request but keeps its drafts for the next time", async () => {
    await session.addDraft("src/app.ts", 5, 5, "Nit");
    session.close();
    assert.strictEqual(session.current, undefined);
    await session.open(1);
    assert.deepStrictEqual(
      session
        .require()
        .drafts.all()
        .map((d) => d.body),
      ["Nit"],
    );
  });

  test("an open still in flight when the review is exited does not bring the pull request back", async () => {
    const opening = session.open(1);
    session.close();
    await opening;
    assert.strictEqual(session.current, undefined);
  });
});
