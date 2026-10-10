import * as assert from "node:assert/strict";
import { describeError, HttpError } from "../http";
import { GitHubProvider } from "../providers/github";
import type { Draft, PullRequest } from "../providers/types";
import { stubFetch } from "./fetch";

const pr: PullRequest = {
  id: 7,
  title: "Fix",
  author: "ana",
  updatedAt: "2026-09-01T00:00:00Z",
  url: "https://github.com/octo/app/pull/7",
  body: "",
  isDraft: false,
  baseSha: "b".repeat(40),
  headSha: "h".repeat(40),
  fetchRefspecs: [],
  checkoutBranch: "fix",
  headBranch: "fix",
  nodeId: "PR_kw7",
};

/** GitHub's pull request, from the branch "fix" of `headRepo`. */
function ghPull(headRepo: string | null) {
  return {
    number: 7,
    title: "Fix",
    user: { login: "ana" },
    updated_at: pr.updatedAt,
    html_url: pr.url,
    body: null,
    draft: false,
    node_id: "PR_kw7",
    base: { ref: "main", sha: pr.baseSha, repo: { full_name: "octo/app" } },
    head: { ref: "fix", sha: pr.headSha, repo: headRepo === null ? null : { full_name: headRepo } },
  };
}

function thread(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    isResolved: false,
    isOutdated: false,
    path: "src/app.ts",
    line: 5,
    startLine: null,
    originalLine: 5,
    originalStartLine: null,
    diffSide: "RIGHT",
    comments: {
      nodes: [{ id: `${id}-c`, body: "Why?", createdAt: "2026-09-01T00:00:00Z", author: { login: "ana" }, originalCommit: { oid: "o".repeat(40) } }],
    },
    ...overrides,
  };
}

function draft(id: string, startLine: number, endLine: number): Draft {
  return { id, path: "src/app.ts", startLine, endLine, body: `note ${id}`, headSha: pr.headSha, origin: "manual" };
}

suite("GitHubProvider", () => {
  let fetchStub: ReturnType<typeof stubFetch> | undefined;
  const provider = () => new GitHubProvider("octo", "app", async () => "tok");

  teardown(() => fetchStub?.restore());

  test("lists pull requests waiting for my review", async () => {
    fetchStub = stubFetch(() => ({
      body: { items: [{ number: 7, title: "Fix", user: { login: "ana" }, updated_at: pr.updatedAt, html_url: pr.url }] },
    }));
    const prs = await provider().listPullRequests("assigned");
    assert.deepStrictEqual(prs, [{ id: 7, title: "Fix", author: "ana", updatedAt: pr.updatedAt, url: pr.url }]);
    const url = new URL(fetchStub.calls[0].url);
    assert.strictEqual(url.pathname, "/search/issues");
    assert.strictEqual(url.searchParams.get("q"), "repo:octo/app is:pr is:open review-requested:@me");
    assert.strictEqual((fetchStub.calls[0].init.headers as Record<string, string>).Authorization, "Bearer tok");
  });

  test("maps a pull request and the refs to fetch; its branch is checked out under its own name", async () => {
    fetchStub = stubFetch(() => ({ body: ghPull("octo/app") }));
    const result = await provider().getPullRequest(7);
    assert.strictEqual(fetchStub.calls[0].url, "https://api.github.com/repos/octo/app/pulls/7");
    assert.deepStrictEqual(result, {
      ...pr,
      fetchRefspecs: ["+refs/heads/main:refs/smart-pr-review/7/base", "+refs/pull/7/head:refs/smart-pr-review/7/head"],
    });
  });

  test("a fork's pull request (or one whose fork is gone) is checked out as pr/<id>, tracking nothing", async () => {
    for (const headRepo of ["ana/app", null]) {
      fetchStub = stubFetch(() => ({ body: ghPull(headRepo) }));
      const result = await provider().getPullRequest(7);
      assert.deepStrictEqual([result.checkoutBranch, result.headBranch], ["pr/7", undefined]);
    }
  });

  test("reads every page of review threads", async () => {
    let page = 0;
    fetchStub = stubFetch(() => {
      const reviewThreads =
        page++ === 0
          ? { pageInfo: { hasNextPage: true, endCursor: "c1" }, nodes: [thread("T1")] }
          : {
              pageInfo: { hasNextPage: false, endCursor: null },
              nodes: [
                thread("T2", { line: null, isOutdated: true, originalLine: 7, originalStartLine: 6 }),
                thread("T3", { line: 2, diffSide: "LEFT", isResolved: true }),
              ],
            };
      return { body: { data: { repository: { pullRequest: { reviewThreads } } } } };
    });
    const threads = await provider().getThreads(pr);
    assert.deepStrictEqual(
      threads.map((t) => [t.id, t.side, t.line, t.isOutdated, t.isResolved]),
      [
        ["T1", "head", 5, false, false],
        ["T2", "head", undefined, true, false],
        ["T3", "base", 2, false, true],
      ],
    );
    assert.deepStrictEqual(threads[0].comments, [{ id: "T1-c", author: "ana", body: "Why?", createdAt: "2026-09-01T00:00:00Z" }]);
    // An outdated thread keeps where it was written; a current one needs no such place.
    assert.deepStrictEqual(
      threads.map((t) => t.original),
      [undefined, { sha: "o".repeat(40), line: 7, startLine: 6 }, undefined],
    );
    assert.strictEqual(fetchStub.body(1).variables.after, "c1");
  });

  test("replies, resolves and reopens threads", async () => {
    fetchStub = stubFetch(() => ({ body: { data: {} } }));
    await provider().reply(pr, "T1", "Done");
    await provider().setThreadResolved(pr, "T1", true);
    await provider().setThreadResolved(pr, "T1", false);
    assert.match(fetchStub.body(0).query, /addPullRequestReviewThreadReply/);
    assert.deepStrictEqual(fetchStub.body(0).variables, { id: "T1", body: "Done" });
    assert.match(fetchStub.body(1).query, /\bresolveReviewThread/);
    assert.match(fetchStub.body(2).query, /unresolveReviewThread/);
  });

  test("surfaces GraphQL errors", async () => {
    fetchStub = stubFetch(() => ({ body: { errors: [{ message: "Resource not accessible" }] } }));
    await assert.rejects(provider().reply(pr, "T1", "x"), /Resource not accessible/);
  });

  test("reads and updates viewed files", async () => {
    fetchStub = stubFetch(() => ({
      body: {
        data: {
          repository: {
            pullRequest: {
              files: {
                pageInfo: { hasNextPage: false, endCursor: null },
                nodes: [
                  { path: "a.ts", viewerViewedState: "VIEWED" },
                  { path: "b.ts", viewerViewedState: "DISMISSED" },
                ],
              },
            },
          },
        },
      },
    }));
    assert.deepStrictEqual([...(await provider().getViewedFiles(pr))], ["a.ts"]);
    await provider().setFileViewed(pr, "b.ts", true);
    assert.match(fetchStub.body(1).query, /markFileAsViewed/);
    assert.deepStrictEqual(fetchStub.body(1).variables, { id: "PR_kw7", path: "b.ts" });
  });

  test("publishes a review with single and multi-line comments", async () => {
    fetchStub = stubFetch(() => ({ body: { id: 1 } }));
    const result = await provider().publishReview(pr, "LGTM", [draft("d1", 4, 4), draft("d2", 7, 9)], "approve");
    assert.strictEqual(fetchStub.calls[0].url, "https://api.github.com/repos/octo/app/pulls/7/reviews");
    assert.deepStrictEqual(fetchStub.body(0), {
      commit_id: pr.headSha,
      event: "APPROVE",
      body: "LGTM",
      comments: [
        { path: "src/app.ts", body: "note d1", side: "RIGHT", line: 4 },
        { path: "src/app.ts", body: "note d2", side: "RIGHT", line: 9, start_line: 7, start_side: "RIGHT" },
      ],
    });
    assert.deepStrictEqual(result, { published: ["d1", "d2"], failed: [] });
  });

  test("requires a summary to request changes", async () => {
    fetchStub = stubFetch(() => ({ body: {} }));
    await assert.rejects(provider().publishReview(pr, "  ", [draft("d1", 4, 4)], "requestChanges"), /summary/);
    assert.strictEqual(fetchStub.calls.length, 0);
  });

  test("requires a summary for a Comment review even with drafts (GitHub: body is required for COMMENT)", async () => {
    fetchStub = stubFetch(() => ({ body: {} }));
    await assert.rejects(provider().publishReview(pr, "", [draft("d1", 4, 4)], "comment"), /summary/);
    assert.strictEqual(fetchStub.calls.length, 0);
  });

  test("propagates a rejected review, e.g. approving your own pull request", async () => {
    fetchStub = stubFetch(() => ({
      status: 422,
      body: { message: "Unprocessable Entity", errors: ["Can not approve your own pull request"] },
    }));
    await assert.rejects(
      provider().publishReview(pr, "", [draft("d1", 4, 4)], "approve"),
      (e: unknown) => e instanceof HttpError && e.status === 422 && /Can not approve your own/.test(e.message),
    );
  });
});

suite("describeError", () => {
  test("asks to sign in on 401", () => {
    assert.match(describeError(new HttpError(401, "Bad credentials")), /Sign in/);
  });

  test("explains GitHub rate limits", () => {
    const headers = new Headers({ "x-ratelimit-remaining": "0", "x-ratelimit-reset": "1790000000" });
    assert.match(describeError(new HttpError(403, "API rate limit exceeded", headers)), /rate limit/);
  });

  test("explains a 403 without provider-specific advice", () => {
    const message = describeError(new HttpError(403, "Resource not accessible"));
    assert.match(message, /Permission denied: Resource not accessible/);
    assert.doesNotMatch(message, /Azure DevOps/);
  });

  test("keeps plain errors as they are", () => {
    assert.strictEqual(describeError(new Error("boom")), "boom");
  });
});
