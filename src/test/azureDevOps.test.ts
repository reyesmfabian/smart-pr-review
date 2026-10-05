import * as assert from "node:assert/strict";
import { HttpError } from "../http";
import { AzureDevOpsProvider } from "../providers/azureDevOps";
import type { Draft, PullRequest } from "../providers/types";
import type { RemoteInfo } from "../remote";
import { memento } from "./fakes";
import { stubFetch, type StubResponse } from "./fetch";

const cloud: RemoteInfo = {
  kind: "azureDevOps",
  host: "dev.azure.com",
  origin: "https://dev.azure.com",
  owner: "contoso",
  project: "My Project",
  repo: "web",
};
const REPO_API = "https://dev.azure.com/contoso/My%20Project/_apis/git/repositories/web";

const pr: PullRequest = {
  id: 7,
  title: "Fix",
  author: "Ana",
  updatedAt: "2026-09-01T00:00:00Z",
  url: "https://dev.azure.com/contoso/My%20Project/_git/web/pullrequest/7",
  body: "",
  isDraft: false,
  baseSha: "b".repeat(40),
  headSha: "h".repeat(40),
  fetchRefspecs: [],
  checkoutBranch: "feature/fix",
  headBranch: "feature/fix",
  nodeId: "7",
};

function adoPullRequest(overrides: Record<string, unknown> = {}) {
  return {
    pullRequestId: 7,
    title: "Fix",
    description: "Details",
    createdBy: { displayName: "Ana" },
    creationDate: "2026-09-01T00:00:00Z",
    sourceRefName: "refs/heads/feature/fix",
    targetRefName: "refs/heads/main",
    lastMergeSourceCommit: { commitId: pr.headSha },
    lastMergeTargetCommit: { commitId: pr.baseSha },
    ...overrides,
  };
}

function draft(id: string, startLine: number, endLine: number): Draft {
  return { id, path: "src/app.ts", startLine, endLine, body: `note ${id}`, headSha: pr.headSha, origin: "manual" };
}

/** Routes by "METHOD path-fragment"; the first matching key wins. */
function route(routes: Record<string, StubResponse | (() => StubResponse)>) {
  return stubFetch((url, init) => {
    const method = init.method ?? "GET";
    const key = Object.keys(routes).find((k) => {
      const [m, fragment] = k.split(" ");
      return m === method && url.includes(fragment);
    });
    if (!key) {
      return { status: 404, body: { message: `no route for ${method} ${url}` } };
    }
    const r = routes[key];
    return typeof r === "function" ? r() : r;
  });
}

suite("AzureDevOpsProvider", () => {
  let fetchStub: ReturnType<typeof stubFetch> | undefined;
  const provider = (remote = cloud) => new AzureDevOpsProvider(remote, async () => "Bearer tok", memento());

  teardown(() => fetchStub?.restore());

  test("lists active pull requests waiting for my review", async () => {
    fetchStub = route({
      "GET /_apis/connectionData": { body: { authenticatedUser: { id: "me-1" } } },
      "GET /pullrequests?": { body: { value: [adoPullRequest()] } },
    });
    const prs = await provider().listPullRequests("assigned");
    assert.deepStrictEqual(prs, [{ id: 7, title: "Fix", author: "Ana", updatedAt: pr.updatedAt, url: pr.url }]);
    assert.strictEqual(fetchStub.calls[0].url, "https://dev.azure.com/contoso/_apis/connectionData");
    const listCall = fetchStub.calls.find((c) => c.url.includes("/pullrequests?"))!;
    const list = new URL(listCall.url);
    assert.strictEqual(`${list.origin}${list.pathname}`, `${REPO_API}/pullrequests`);
    assert.strictEqual(list.searchParams.get("searchCriteria.status"), "active");
    assert.strictEqual(list.searchParams.get("searchCriteria.reviewerId"), "me-1");
    assert.strictEqual(list.searchParams.get("api-version"), "6.0");
    assert.strictEqual((listCall.init.headers as Record<string, string>).Authorization, "Bearer tok");
  });

  test("Assigned to me includes pull requests assigned to my teams, newest first, without duplicates", async () => {
    const byReviewer: Record<string, unknown[]> = {
      "me-1": [
        adoPullRequest({ pullRequestId: 1, creationDate: "2026-09-01T00:00:00Z" }),
        adoPullRequest({ pullRequestId: 2, creationDate: "2026-09-03T00:00:00Z" }),
      ],
      "team-1": [
        adoPullRequest({ pullRequestId: 2, creationDate: "2026-09-03T00:00:00Z" }),
        adoPullRequest({ pullRequestId: 3, creationDate: "2026-09-02T00:00:00Z" }),
      ],
    };
    fetchStub = stubFetch((url) => {
      if (url.includes("/_apis/connectionData")) {
        return { body: { authenticatedUser: { id: "me-1" } } };
      }
      if (url.includes("/teams?")) {
        return { body: { value: [{ id: "team-1", name: "Web" }] } };
      }
      return { body: { value: byReviewer[new URL(url).searchParams.get("searchCriteria.reviewerId") ?? ""] ?? [] } };
    });
    const prs = await provider().listPullRequests("assigned");
    assert.deepStrictEqual(
      prs.map((p) => p.id),
      [2, 3, 1],
    );
    assert.ok(fetchStub.calls.some((c) => c.url === "https://dev.azure.com/contoso/_apis/projects/My%20Project/teams?$mine=true&api-version=6.0"));
  });

  test("Assigned to me falls back to my own reviews when my teams cannot be read", async () => {
    fetchStub = stubFetch((url) => {
      if (url.includes("/_apis/connectionData")) {
        return { body: { authenticatedUser: { id: "me-1" } } };
      }
      if (url.includes("/teams?")) {
        return { status: 403, body: { message: "token lacks project scope" } };
      }
      return { body: { value: [adoPullRequest()] } };
    });
    assert.deepStrictEqual(
      (await provider().listPullRequests("assigned")).map((p) => p.id),
      [7],
    );
  });

  test("asks who the user is again when the credentials change", async () => {
    fetchStub = stubFetch((url, init) => {
      const authorization = (init.headers as Record<string, string>).Authorization;
      return url.includes("/_apis/connectionData")
        ? { body: { authenticatedUser: { id: authorization === "Basic A" ? "me-A" : "me-B" } } }
        : { body: { value: [] } };
    });
    let authorization = "Basic A";
    const ado = new AzureDevOpsProvider(cloud, async () => authorization, memento());
    await ado.listPullRequests("assigned");
    await ado.listPullRequests("created");
    authorization = "Basic B";
    await ado.listPullRequests("assigned");
    const reviewers = fetchStub.calls.map((c) => new URL(c.url).searchParams).filter((p) => p.has("searchCriteria.status"));
    assert.deepStrictEqual(
      reviewers.map((p) => p.get("searchCriteria.reviewerId") ?? p.get("searchCriteria.creatorId")),
      ["me-A", "me-A", "me-B"],
    );
    assert.strictEqual(fetchStub.calls.filter((c) => c.url.includes("/_apis/connectionData")).length, 2);
  });

  test("adds the personal access token scope to Azure DevOps permission errors", async () => {
    fetchStub = route({ "GET /pullrequests?": { status: 403, body: { message: "TF401027: You need the Git GenericRead permission" } } });
    await assert.rejects(
      provider().listPullRequests("all"),
      (e: unknown) => e instanceof HttpError && e.status === 403 && /GenericRead.*Code \(Read & Write\)/.test(e.message),
    );
  });

  test("builds Azure DevOps Server URLs from the remote origin and collection", async () => {
    fetchStub = route({ "GET /pullrequests?": { body: { value: [] } } });
    const server: RemoteInfo = { ...cloud, host: "tfs.contoso.com", origin: "http://tfs.contoso.com:8080", owner: "tfs/DefaultCollection" };
    await provider(server).listPullRequests("all");
    assert.ok(
      fetchStub.calls[0].url.startsWith("http://tfs.contoso.com:8080/tfs/DefaultCollection/My%20Project/_apis/git/repositories/web/pullrequests?"),
    );
  });

  test("maps a pull request and fetches its source branch", async () => {
    fetchStub = route({ "GET /pullrequests/7": { body: adoPullRequest() } });
    assert.deepStrictEqual(await provider().getPullRequest(7), {
      ...pr,
      body: "Details",
      fetchRefspecs: ["+refs/heads/main:refs/smart-pr-review/7/base", "+refs/heads/feature/fix:refs/smart-pr-review/7/head"],
    });
  });

  test("fetches a fork pull request head from the fork repository and checks it out as pr/<id>", async () => {
    // The merge ref may be missing when the pull request has conflicts; the fork always has the branch.
    const forkSource = { name: "refs/heads/fix", repository: { remoteUrl: "https://dev.azure.com/ana/My%20Project/_git/web-fork" } };
    fetchStub = route({ "GET /pullrequests/7": { body: adoPullRequest({ forkSource }) } });
    const result = await provider().getPullRequest(7);
    assert.deepStrictEqual(result.fetchRefspecs, ["+refs/heads/main:refs/smart-pr-review/7/base"]);
    assert.deepStrictEqual(result.headFetch, { url: forkSource.repository.remoteUrl, refspec: "+refs/heads/fix:refs/smart-pr-review/7/head" });
    assert.deepStrictEqual([result.checkoutBranch, result.headBranch], ["pr/7", undefined]);
  });

  test("asks to retry while Azure DevOps has not computed the pull request commits", async () => {
    fetchStub = route({ "GET /pullrequests/7": { body: adoPullRequest({ lastMergeSourceCommit: undefined }) } });
    await assert.rejects(provider().getPullRequest(7), /still preparing/);
  });

  test("maps file threads tracked to the latest iteration and hides system and pull-request-level threads", async () => {
    const text = (content: string) => ({
      id: 1,
      content,
      publishedDate: "2026-09-01T00:00:00Z",
      author: { displayName: "Ana" },
      commentType: "text",
    });
    const at = (line: number) => ({ line, offset: 1 });
    const first = "a".repeat(40);
    fetchStub = route({
      "GET /iterations?": {
        body: {
          value: [
            { id: 1, sourceRefCommit: { commitId: first } },
            { id: 3, sourceRefCommit: { commitId: pr.headSha } },
          ],
        },
      },
      "GET /threads?": {
        body: {
          value: [
            {
              id: 1,
              status: "active",
              threadContext: { filePath: "/src/app.ts", rightFileStart: at(4), rightFileEnd: at(6) },
              comments: [text("Why?")],
            },
            { id: 2, comments: [{ ...text("Ana voted 10"), commentType: "system" }] },
            { id: 3, status: "active", threadContext: null, comments: [text("Looks good overall")] },
            {
              id: 4,
              status: "fixed",
              threadContext: { filePath: "/src/old.ts", leftFileStart: at(2), leftFileEnd: at(2) },
              comments: [text("Removed?")],
            },
            {
              // From an earlier iteration, with the position Azure DevOps gives for the latest one and nothing to say it moved: shown there.
              id: 5,
              status: "active",
              threadContext: { filePath: "/src/app.ts", rightFileStart: at(9), rightFileEnd: at(9) },
              pullRequestThreadContext: { iterationContext: { secondComparingIteration: 1 } },
              comments: [text("Old, file unchanged since")],
            },
            {
              id: 6,
              status: "active",
              threadContext: { filePath: "/src/app.ts", rightFileStart: at(12), rightFileEnd: at(12) },
              pullRequestThreadContext: { iterationContext: { secondComparingIteration: 1 }, trackingCriteria: { secondComparingIteration: 3 } },
              comments: [text("Old but tracked"), { ...text("deleted"), id: 2, isDeleted: true }],
            },
            {
              // Tracking failed ("tracked if greater than 0"): outdated, kept where it was written, in the iteration's commit.
              id: 7,
              status: "active",
              threadContext: { filePath: "/src/app.ts", rightFileStart: at(2), rightFileEnd: at(3) },
              pullRequestThreadContext: {
                iterationContext: { secondComparingIteration: 1 },
                trackingCriteria: { secondComparingIteration: 0, origRightFileStart: at(2), origRightFileEnd: at(3) },
              },
              comments: [text("Old and lost")],
            },
            // No position at all (a comment on the file): nowhere to show it.
            { id: 8, status: "active", threadContext: { filePath: "/src/app.ts" }, comments: [text("About this file")] },
          ],
        },
      },
    });
    const threads = await provider().getThreads(pr);
    assert.ok(fetchStub.calls[1].url.includes("/pullRequests/7/threads?$iteration=3&$baseIteration=0&api-version=6.0"));
    assert.deepStrictEqual(
      threads.map((t) => [t.id, t.path, t.side, t.startLine, t.line, t.isOutdated, t.isResolved, t.comments.length]),
      [
        ["1", "src/app.ts", "head", 4, 6, false, false, 1],
        ["4", "src/old.ts", "base", 2, 2, false, true, 1],
        ["5", "src/app.ts", "head", 9, 9, false, false, 1],
        ["6", "src/app.ts", "head", 12, 12, false, false, 1],
        ["7", "src/app.ts", "head", undefined, undefined, true, false, 1],
        ["8", "src/app.ts", "head", undefined, undefined, true, false, 1],
      ],
    );
    assert.deepStrictEqual(
      threads.map((t) => t.original),
      [undefined, undefined, undefined, undefined, { sha: first, line: 3, startLine: 2 }, undefined],
    );
  });

  test("replies to, resolves and reopens threads", async () => {
    fetchStub = route({ "POST /threads/5/comments": { body: {} }, "PATCH /threads/5": { body: {} } });
    await provider().reply(pr, "5", "Done");
    await provider().setThreadResolved(pr, "5", true);
    await provider().setThreadResolved(pr, "5", false);
    assert.ok(fetchStub.calls[0].url.startsWith(`${REPO_API}/pullRequests/7/threads/5/comments?api-version=6.0`));
    assert.deepStrictEqual(fetchStub.body(0), { content: "Done", parentCommentId: 1, commentType: 1 });
    assert.deepStrictEqual(fetchStub.body(1), { status: 2 });
    assert.deepStrictEqual(fetchStub.body(2), { status: 1 });
  });

  test("keeps viewed files on this machine", async () => {
    fetchStub = route({});
    const ado = provider();
    await ado.setFileViewed(pr, "src/app.ts", true);
    await ado.setFileViewed(pr, "src/other.ts", true);
    await ado.setFileViewed(pr, "src/other.ts", false);
    assert.deepStrictEqual([...(await ado.getViewedFiles(pr))], ["src/app.ts"]);
    assert.strictEqual(fetchStub.calls.length, 0);
  });

  test("publishes drafts anchored to the latest iteration, then the vote, then the summary", async () => {
    fetchStub = route({
      "GET /iterations/2/changes": { body: { changeEntries: [{ changeTrackingId: 5, item: { path: "/src/app.ts" } }], nextSkip: 0 } },
      "GET /iterations?": { body: { value: [{ id: 1 }, { id: 2 }] } },
      "POST /threads": { body: {} },
      "GET /_apis/connectionData": { body: { authenticatedUser: { id: "me-1" } } },
      "PUT /reviewers/me-1": { body: {} },
    });
    const result = await provider().publishReview(pr, "LGTM", [{ ...draft("d1", 4, 6), startColumn: 3, endColumn: 8 }], "approve");
    assert.deepStrictEqual(result, { published: ["d1", "vote", "summary"], failed: [] });
    const writes = fetchStub.calls.map((c, i) => ({ c, i })).filter(({ c }) => c.init.method === "POST" || c.init.method === "PUT");
    assert.deepStrictEqual(
      writes.map(({ c }) => `${c.init.method} ${new URL(c.url).pathname.split("/pullRequests/7/")[1]}`),
      ["POST threads", "PUT reviewers/me-1", "POST threads"],
    );
    assert.deepStrictEqual(fetchStub.body(writes[0].i), {
      comments: [{ parentCommentId: 0, content: "note d1", commentType: 1 }],
      status: 1,
      threadContext: { filePath: "/src/app.ts", rightFileStart: { line: 4, offset: 3 }, rightFileEnd: { line: 6, offset: 8 } },
      pullRequestThreadContext: { changeTrackingId: 5, iterationContext: { firstComparingIteration: 1, secondComparingIteration: 2 } },
    });
    // Same body as the official reviewers example: the reviewer id goes in the body too.
    assert.deepStrictEqual(fetchStub.body(writes[1].i), { vote: 10, id: "me-1" });
    assert.deepStrictEqual(fetchStub.body(writes[2].i), { comments: [{ parentCommentId: 0, content: "LGTM", commentType: 1 }], status: 1 });
  });

  test("holds back the vote and summary when a draft fails, so the retry sends them together", async () => {
    let posts = 0;
    fetchStub = route({
      "GET /iterations/1/changes": { body: { changeEntries: [] } },
      "GET /iterations?": { body: { value: [{ id: 1 }] } },
      "POST /threads": () => (++posts === 2 ? { status: 500, body: { message: "boom" } } : { body: {} }),
    });
    const result = await provider().publishReview(pr, "LGTM", [draft("d1", 4, 4), draft("d2", 8, 8)], "approve");
    assert.deepStrictEqual(result.published, ["d1"]);
    assert.deepStrictEqual(
      result.failed.map((f) => f.id),
      ["d2"],
    );
    assert.match(result.failed[0].error, /boom/);
    assert.strictEqual(fetchStub.calls.filter((c) => c.init.method === "PUT").length, 0);
    assert.strictEqual(posts, 2);
  });

  test("stops at the first failed draft: with 3 of 5 failing, 2 are published and 3 stay as drafts (spec §11)", async () => {
    let posts = 0;
    fetchStub = route({
      "GET /iterations/1/changes": { body: { changeEntries: [] } },
      "GET /iterations?": { body: { value: [{ id: 1 }] } },
      "POST /threads": () => (++posts === 3 ? { status: 500, body: { message: "boom" } } : { body: {} }),
    });
    const drafts = [1, 2, 3, 4, 5].map((n) => draft(`d${n}`, n, n));
    const result = await provider().publishReview(pr, "", drafts, "comment");
    assert.deepStrictEqual(result.published, ["d1", "d2"]);
    assert.deepStrictEqual(result.failed, [{ id: "d3", error: "src/app.ts:3 — Request failed (500): boom" }]);
    assert.strictEqual(posts, 3);
  });

  test("reports a failed vote without sending the summary", async () => {
    fetchStub = route({
      "GET /_apis/connectionData": { body: { authenticatedUser: { id: "me-1" } } },
      "PUT /reviewers/me-1": { status: 403, body: { message: "not a reviewer" } },
      "POST /threads": { body: {} },
    });
    const result = await provider().publishReview(pr, "LGTM", [], "reject");
    assert.deepStrictEqual(
      result.failed.map((f) => f.id),
      ["vote"],
    );
    assert.match(result.failed[0].error, /^Vote — Permission denied: not a reviewer/);
    assert.strictEqual(fetchStub.calls.filter((c) => c.init.method === "POST").length, 0);
  });

  test("sends no vote for a plain comment, and refuses an empty review", async () => {
    fetchStub = route({ "POST /threads": { body: {} } });
    assert.deepStrictEqual(await provider().publishReview(pr, "Thanks", [], "comment"), { published: ["summary"], failed: [] });
    await assert.rejects(provider().publishReview(pr, " ", [], "comment"), /Nothing to publish/);
    assert.strictEqual(fetchStub.calls.length, 1);
  });
});
