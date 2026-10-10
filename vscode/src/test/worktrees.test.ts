import * as assert from "node:assert/strict";
import { access, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fetchRefs, git, headSha } from "../git";
import { checkReviewWorktree, createReviewWorktree, removeReviewWorktree, reviewWorktrees, type ReviewWorktree } from "../review/worktrees";
import { makeRepo, type TestRepo } from "./repo";

suite("review worktrees", () => {
  let repo: TestRepo;
  let w: ReviewWorktree;
  setup(async () => {
    repo = await makeRepo();
    await fetchRefs(repo.root, "origin", ["+refs/pull/1/head:refs/smart-pr-review/1/head"]);
    await writeFile(join(repo.root, "local.txt"), "Original checkout stays dirty");
    w = await createReviewWorktree(repo.root, 1, repo.headSha);
  });
  teardown(async () => { await rm(dirname(repo.root), { recursive: true, force: true }); });

  test("creates a detached worktree without changing the original checkout and survives a reopen", async () => {
    assert.equal(await headSha(repo.root), repo.baseSha);
    assert.equal(await headSha(w.path), repo.headSha);
    assert.equal((await git(w.path, ["rev-parse", "--abbrev-ref", "HEAD"])).trim(), "HEAD");
    assert.deepEqual(await reviewWorktrees(repo.root), [w]);
    assert.deepEqual(await reviewWorktrees(w.path), [w]);
    await removeReviewWorktree(repo.root, (await reviewWorktrees(repo.root))[0]);
    await assert.rejects(access(w.path));
    assert.deepEqual(await reviewWorktrees(repo.root), []);
    await access(join(repo.root, "local.txt"));
  });

  test("never removes the worktree hosting the current repository", async () => {
    await assert.rejects(removeReviewWorktree(w.path, w), /original repository/);
    await access(w.path);
  });

  test("refuses tracked, untracked and ignored local files", async () => {
    const tracked = join(w.path, "src/app.ts");
    await writeFile(tracked, "work in progress");
    await assert.rejects(removeReviewWorktree(repo.root, w), /nothing was deleted/);
    await git(w.path, ["restore", "src/app.ts"]);
    const local = join(w.path, "local.txt");
    await writeFile(local, "keep");
    await assert.rejects(removeReviewWorktree(repo.root, w), /nothing was deleted/);
    await writeFile(join(repo.root, ".git/info/exclude"), "local.txt\n");
    await assert.rejects(removeReviewWorktree(repo.root, w), /nothing was deleted/);
    await access(local);
  });

  test("preserves new commits even after resetting HEAD to the original revision", async () => {
    await git(w.path, ["commit", "--allow-empty", "-m", "keep this"]);
    await assert.rejects(removeReviewWorktree(repo.root, w), /nothing was deleted/);
    await git(w.path, ["reset", "--hard", repo.headSha]);
    await assert.rejects(removeReviewWorktree(repo.root, w), /nothing was deleted/);
  });

  test("does not accept a forged path and preserves other worktree records", async () => {
    await assert.rejects(checkReviewWorktree(repo.root, { ...w, path: repo.root }), /registered/);
    const second = await createReviewWorktree(repo.root, 2, repo.headSha);
    await removeReviewWorktree(repo.root, w);
    assert.deepEqual(await reviewWorktrees(repo.root), [second]);
  });

  test("failed creation leaves no registry record, and invalid revisions are rejected", async () => {
    await assert.rejects(createReviewWorktree(repo.root, 2, "0".repeat(40)));
    await assert.rejects(createReviewWorktree(repo.root, 2, "--help"));
    assert.deepEqual(await reviewWorktrees(repo.root), [w]);
  });
});
