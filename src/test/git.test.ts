import * as assert from "node:assert/strict";
import { symlink } from "node:fs/promises";
import { dirname, join } from "node:path";
import {
  changedFiles,
  changedRanges,
  diffRanges,
  fetchRefs,
  git,
  hasCommit,
  mergeBase,
  parseChangedRanges,
  parseHunkRanges,
  parseNameStatus,
  pickRemote,
  repositoryRoot,
  showFile,
} from "../git";
import { makeRepo } from "./repo";

suite("git", () => {
  test("parseNameStatus handles renames, spaces and deletions", () => {
    const output = "M\0src/app.ts\0A\0src/new file.ts\0R087\0docs/old name.md\0docs/new name.md\0D\0keep.txt\0";
    assert.deepStrictEqual(parseNameStatus(output), [
      { status: "M", path: "src/app.ts" },
      { status: "A", path: "src/new file.ts" },
      { status: "R", oldPath: "docs/old name.md", path: "docs/new name.md" },
      { status: "D", path: "keep.txt" },
    ]);
  });

  test("parseHunkRanges reads head-side ranges and skips pure deletions", () => {
    const diff = "@@ -1,3 +1,4 @@\n x\n@@ -10 +11 @@\n-y\n+z\n@@ -20,2 +22,0 @@\n-a\n-b\n";
    assert.deepStrictEqual(parseHunkRanges(diff), [
      { start: 1, end: 4 },
      { start: 11, end: 11 },
    ]);
  });

  test("parseChangedRanges reads the lines a -U0 diff adds or changes; a deletion counts as the lines around it", () => {
    const diff = "@@ -1,0 +1,3 @@\n+a\n+b\n+c\n@@ -10 +13 @@\n-y\n+z\n@@ -20,2 +22,0 @@\n-a\n-b\n@@ -1 +0,0 @@\n-only\n";
    assert.deepStrictEqual(parseChangedRanges(diff), [
      { start: 1, end: 3 },
      { start: 13, end: 13 },
      { start: 22, end: 23 },
      { start: 1, end: 1 },
    ]);
  });

  test("reads a pull request from a real repository", async () => {
    const repo = await makeRepo();
    assert.strictEqual(await hasCommit(repo.root, repo.headSha), false);
    await fetchRefs(repo.root, "origin", ["+refs/pull/1/head:refs/smart-pr-review/1/head"]);
    assert.strictEqual(await hasCommit(repo.root, repo.headSha), true);

    const base = await mergeBase(repo.root, repo.baseSha, repo.headSha);
    assert.strictEqual(base, repo.baseSha);
    const files = await changedFiles(repo.root, base, repo.headSha);
    assert.deepStrictEqual(files.map((f) => `${f.status} ${f.path}`).sort(), [
      "A src/new file.ts",
      "D keep.txt",
      "M src/app.ts",
      "R docs/new name.md",
    ]);
    assert.strictEqual(await showFile(repo.root, repo.headSha, "keep.txt"), "");
    assert.match(await showFile(repo.root, repo.headSha, "src/app.ts"), /changed line 5/);
    assert.deepStrictEqual(await diffRanges(repo.root, base, repo.headSha, { status: "M", path: "src/app.ts" }), [{ start: 2, end: 8 }]);
    // Only line 5 changed: lines 2-4 and 6-8 are context GitHub accepts comments on, untouched code.
    assert.deepStrictEqual(await changedRanges(repo.root, base, repo.headSha, { status: "M", path: "src/app.ts" }), [{ start: 5, end: 5 }]);
    assert.deepStrictEqual(await changedRanges(repo.root, base, repo.headSha, { status: "A", path: "src/new file.ts" }), [{ start: 1, end: 1 }]);
    const renamed = files.find((f) => f.status === "R")!;
    assert.deepStrictEqual(await diffRanges(repo.root, base, repo.headSha, renamed), []);
    assert.deepStrictEqual(await changedRanges(repo.root, base, repo.headSha, renamed), []);
  });

  test("computes the same diff ranges as GitHub whatever the user git config says", async () => {
    const repo = await makeRepo();
    await fetchRefs(repo.root, "origin", ["+refs/pull/1/head:refs/smart-pr-review/1/head"]);
    for (const [key, value] of [
      ["color.ui", "always"],
      ["diff.context", "10"],
      ["diff.external", "false"],
    ]) {
      await git(repo.root, ["config", key, value]);
    }
    const ranges = await diffRanges(repo.root, repo.baseSha, repo.headSha, { status: "M", path: "src/app.ts" });
    assert.deepStrictEqual(ranges, [{ start: 2, end: 8 }]);
  });

  test("treats file paths literally, not as globs", async () => {
    const repo = await makeRepo();
    await fetchRefs(repo.root, "origin", ["+refs/pull/1/head:refs/smart-pr-review/1/head"]);
    assert.deepStrictEqual(await diffRanges(repo.root, repo.baseSha, repo.headSha, { status: "M", path: "src/app.t[s]" }), []);
  });

  test("keeps the workspace spelling of the repository root when it is reached through a symlink", async () => {
    const repo = await makeRepo();
    const link = join(dirname(repo.root), "linked");
    await symlink(repo.root, link);
    // git reports the resolved path; documents opened from the workspace use the symlinked one.
    assert.strictEqual(await repositoryRoot(link), link);
    assert.strictEqual(await repositoryRoot(join(link, "src")), link);
  });

  test("prefers the upstream remote of a fork clone", async () => {
    const repo = await makeRepo();
    assert.strictEqual((await pickRemote(repo.root))?.name, "origin");
    await git(repo.root, ["remote", "add", "upstream", "https://github.com/octo/app.git"]);
    assert.deepStrictEqual(await pickRemote(repo.root), { name: "upstream", url: "https://github.com/octo/app.git" });
  });
});
