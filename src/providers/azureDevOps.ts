import * as vscode from "vscode";
import { describeError, HttpError, requestJson } from "../http";
import type { RemoteInfo } from "../remote";
import type { Draft, PublishResult, PullRequest, PullRequestFilter, PullRequestProvider, PullRequestSummary, Thread, Vote } from "./types";

/** Supported by Azure DevOps Services and Azure DevOps Server 2020 and later. */
const API_VERSION = "6.0";

interface AdoIdentity {
  displayName: string;
}
interface AdoPullRequest {
  pullRequestId: number;
  title: string;
  description?: string;
  createdBy: AdoIdentity;
  creationDate: string;
  sourceRefName: string;
  targetRefName: string;
  isDraft?: boolean;
  lastMergeSourceCommit?: { commitId: string };
  lastMergeTargetCommit?: { commitId: string };
  forkSource?: { name: string; repository?: { remoteUrl?: string } };
}
interface AdoPosition {
  line: number;
  offset: number;
}
interface AdoThread {
  id: number;
  status?: string;
  isDeleted?: boolean;
  threadContext?: {
    filePath?: string;
    rightFileStart?: AdoPosition | null;
    rightFileEnd?: AdoPosition | null;
    leftFileStart?: AdoPosition | null;
    leftFileEnd?: AdoPosition | null;
  } | null;
  pullRequestThreadContext?: {
    iterationContext?: { secondComparingIteration?: number } | null;
    /** Present when Azure DevOps tracked the thread from its iteration to the requested one; "tracked if greater than 0". */
    trackingCriteria?: { secondComparingIteration?: number; origRightFileStart?: AdoPosition | null; origRightFileEnd?: AdoPosition | null } | null;
  } | null;
  comments: { id: number; content?: string; publishedDate: string; author: AdoIdentity; commentType?: string; isDeleted?: boolean }[];
}
interface AdoChanges {
  changeEntries: { changeTrackingId: number; item?: { path?: string } }[];
  nextSkip?: number;
}

const VOTES: Partial<Record<Vote, number>> = { approve: 10, approveWithSuggestions: 5, waitForAuthor: -5, reject: -10 };
const RESOLVED_STATUSES = new Set(["fixed", "wontFix", "closed", "byDesign"]);
// Numeric CommentThreadStatus and CommentType values for request bodies.
const STATUS_ACTIVE = 1;
const STATUS_FIXED = 2;
const COMMENT_TEXT = 1;

export class AzureDevOpsProvider implements PullRequestProvider {
  readonly kind = "azureDevOps";
  readonly supportedVotes: readonly Vote[] = ["comment", "approve", "approveWithSuggestions", "waitForAuthor", "reject"];
  readonly commentableLines = "file";
  /** https://dev.azure.com/{organization} or {server origin}/{collection path} */
  private readonly collection: string;
  private readonly repoApi: string;
  private readonly webBase: string;
  private readonly teamsApi: string;
  private identity: { authorization: string; id: Promise<string> } | undefined;

  constructor(
    remote: RemoteInfo,
    /** Authorization header value, see AzureDevOpsAuth.authorization. */
    private readonly authorization: () => Promise<string>,
    /** Azure DevOps has no API for "viewed" files: the state stays on this machine. */
    private readonly storage: vscode.Memento,
  ) {
    this.collection = `${remote.origin}/${remote.owner.split("/").map(encodeURIComponent).join("/")}`;
    const project = encodeURIComponent(remote.project ?? "");
    const repo = encodeURIComponent(remote.repo);
    this.repoApi = `${this.collection}/${project}/_apis/git/repositories/${repo}`;
    this.webBase = `${this.collection}/${project}/_git/${repo}`;
    this.teamsApi = `${this.collection}/_apis/projects/${project}/teams?$mine=true&api-version=${API_VERSION}`;
  }

  async listPullRequests(filter: PullRequestFilter): Promise<PullRequestSummary[]> {
    // Like the web's "Assigned to me": reviews requested from me or from one of my teams.
    const criteria =
      filter === "all"
        ? [""]
        : filter === "created"
          ? [`&searchCriteria.creatorId=${await this.me()}`]
          : [await this.me(), ...(await this.myTeams())].map((id) => `&searchCriteria.reviewerId=${id}`);
    // ponytail: first 100 per query, no pagination (spec §5.2)
    const pages = await Promise.all(
      criteria.map((c) => this.repo<{ value: AdoPullRequest[] }>(`/pullrequests?searchCriteria.status=active${c}&$top=100`)),
    );
    const unique = new Map(pages.flatMap((p) => p.value).map((p) => [p.pullRequestId, p]));
    // The API has no "last updated" date: newest first by creation (spec §5.2).
    return [...unique.values()]
      .sort((a, b) => b.creationDate.localeCompare(a.creationDate))
      .slice(0, 100)
      .map((p) => this.summary(p));
  }

  /** Ids of my teams in this project; empty when the token cannot read teams. */
  private async myTeams(): Promise<string[]> {
    try {
      const result = await requestJson<{ value: { id: string }[] }>(this.teamsApi, { headers: await this.headers() });
      return result.value.map((team) => team.id);
    } catch {
      return [];
    }
  }

  async getPullRequest(id: number): Promise<PullRequest> {
    const p = await this.repo<AdoPullRequest>(`/pullrequests/${id}`);
    if (!p.lastMergeSourceCommit || !p.lastMergeTargetCommit) {
      throw new Error(vscode.l10n.t("Azure DevOps is still preparing this pull request. Try again in a moment."));
    }
    const base = `+${p.targetRefName}:refs/smart-pr-review/${id}/base`;
    const fork = p.forkSource;
    const forkUrl = fork?.repository?.remoteUrl;
    const headBranch = fork ? undefined : p.sourceRefName.replace(/^refs\/heads\//, "");
    return {
      ...this.summary(p),
      body: p.description ?? "",
      isDraft: p.isDraft ?? false,
      baseSha: p.lastMergeTargetCommit.commitId,
      headSha: p.lastMergeSourceCommit.commitId,
      // A fork's branch is not in this repository: fetch it from the fork (the merge ref is missing when the
      // pull request has conflicts), or from the merge ref when the fork's URL is unknown.
      fetchRefspecs: fork
        ? forkUrl
          ? [base]
          : [base, `+refs/pull/${id}/merge:refs/smart-pr-review/${id}/merge`]
        : [base, `+${p.sourceRefName}:refs/smart-pr-review/${id}/head`],
      ...(fork && forkUrl ? { headFetch: { url: forkUrl, refspec: `+${fork.name}:refs/smart-pr-review/${id}/head` } } : {}),
      checkoutBranch: headBranch ?? `pr/${id}`,
      ...(headBranch === undefined ? {} : { headBranch }),
      nodeId: String(id),
    };
  }

  async getThreads(pr: PullRequest): Promise<Thread[]> {
    const iterations = await this.iterations(pr.id);
    // Ask Azure DevOps to track every thread's position to the latest iteration.
    const result = await this.repo<{ value: AdoThread[] }>(`/pullRequests/${pr.id}/threads?$iteration=${latestOf(iterations)}&$baseIteration=0`);
    return result.value.flatMap((t) => toThread(t, iterations));
  }

  async reply(pr: PullRequest, threadId: string, body: string): Promise<void> {
    await this.repo(`/pullRequests/${pr.id}/threads/${threadId}/comments`, post({ content: body, parentCommentId: 1, commentType: COMMENT_TEXT }));
  }

  async setThreadResolved(pr: PullRequest, threadId: string, resolved: boolean): Promise<void> {
    await this.repo(`/pullRequests/${pr.id}/threads/${threadId}`, {
      method: "PATCH",
      body: JSON.stringify({ status: resolved ? STATUS_FIXED : STATUS_ACTIVE }),
    });
  }

  async getViewedFiles(pr: PullRequest): Promise<Set<string>> {
    return new Set(this.storage.get<string[]>(this.viewedKey(pr), []));
  }

  async setFileViewed(pr: PullRequest, path: string, viewed: boolean): Promise<void> {
    const files = await this.getViewedFiles(pr);
    if (viewed) {
      files.add(path);
    } else {
      files.delete(path);
    }
    await this.storage.update(this.viewedKey(pr), [...files]);
  }

  /**
   * One thread per draft, then the vote, then the summary as a pull request comment. The vote and the
   * summary go only when every draft is in; a failed one is reported as 'vote' or 'summary' so a retry
   * repeats only what is missing (the vote is idempotent and is sent before the summary).
   */
  async publishReview(pr: PullRequest, body: string, drafts: readonly Draft[], vote: Vote): Promise<PublishResult> {
    const value = VOTES[vote];
    if (drafts.length === 0 && !body.trim() && value === undefined) {
      throw new Error(vscode.l10n.t("Nothing to publish: add a comment, a summary or a vote."));
    }
    const result: PublishResult = { published: [], failed: [] };
    if (drafts.length > 0) {
      const iteration = await this.latestIteration(pr.id);
      const tracking = await this.changeTrackingIds(pr.id, iteration);
      for (const draft of drafts) {
        // Stop at the first failure (spec §11): the rest stay as drafts instead of failing one by one.
        if (result.failed.length > 0) {
          break;
        }
        const changeTrackingId = tracking.get(`/${draft.path}`);
        await this.attempt(result, draft.id, `${draft.path}:${draft.startLine}`, () =>
          this.createThread(pr, draft.body, {
            threadContext: {
              filePath: `/${draft.path}`,
              rightFileStart: { line: draft.startLine, offset: draft.startColumn ?? 1 },
              rightFileEnd: { line: draft.endLine, offset: draft.endColumn ?? 1 },
            },
            ...(changeTrackingId === undefined
              ? {}
              : {
                  pullRequestThreadContext: {
                    changeTrackingId,
                    iterationContext: { firstComparingIteration: 1, secondComparingIteration: iteration },
                  },
                }),
          }),
        );
      }
    }
    if (result.failed.length === 0 && value !== undefined) {
      await this.attempt(result, "vote", vscode.l10n.t("Vote"), async () => {
        const id = await this.me();
        await this.repo(`/pullRequests/${pr.id}/reviewers/${id}`, { method: "PUT", body: JSON.stringify({ vote: value, id }) });
      });
    }
    if (result.failed.length === 0 && body.trim()) {
      await this.attempt(result, "summary", vscode.l10n.t("Review summary"), () => this.createThread(pr, body, {}));
    }
    return result;
  }

  /** Runs one part of the review; a failure is recorded as "<what> — <readable error>". */
  private async attempt(result: PublishResult, id: string, label: string, action: () => Promise<unknown>): Promise<void> {
    try {
      await action();
      result.published.push(id);
    } catch (error) {
      result.failed.push({ id, error: `${label} — ${describeError(error)}` });
    }
  }

  private createThread(pr: PullRequest, content: string, context: object): Promise<unknown> {
    return this.repo(
      `/pullRequests/${pr.id}/threads`,
      post({ comments: [{ parentCommentId: 0, content, commentType: COMMENT_TEXT }], status: STATUS_ACTIVE, ...context }),
    );
  }

  /** Iteration id → its source commit (the pull request head at the time). */
  private async iterations(id: number): Promise<Map<number, string | undefined>> {
    const { value } = await this.repo<{ value: { id: number; sourceRefCommit?: { commitId: string } }[] }>(`/pullRequests/${id}/iterations`);
    return new Map(value.map((i) => [i.id, i.sourceRefCommit?.commitId]));
  }

  private async latestIteration(id: number): Promise<number> {
    return latestOf(await this.iterations(id));
  }

  /** Repository path ("/src/a.ts") → changeTrackingId, which anchors new threads to the file across iterations. */
  private async changeTrackingIds(id: number, iteration: number): Promise<Map<string, number>> {
    const ids = new Map<string, number>();
    let skip = 0;
    do {
      const page = await this.repo<AdoChanges>(`/pullRequests/${id}/iterations/${iteration}/changes?$top=2000&$skip=${skip}&$compareTo=0`);
      for (const change of page.changeEntries) {
        if (change.item?.path) {
          ids.set(change.item.path, change.changeTrackingId);
        }
      }
      skip = page.nextSkip ?? 0;
    } while (skip > 0);
    return ids;
  }

  /**
   * Id of the signed-in user (reviewer and creator filters, votes), cached per credential: a new token or
   * Microsoft account means asking again. Forgotten after a failure.
   */
  private async me(): Promise<string> {
    const headers = await this.headers();
    if (this.identity?.authorization !== headers.Authorization) {
      const id = requestJson<{ authenticatedUser: { id: string } }>(`${this.collection}/_apis/connectionData`, { headers }).then(
        (data) => data.authenticatedUser.id,
      );
      this.identity = { authorization: headers.Authorization, id };
      id.catch(() => {
        if (this.identity?.id === id) {
          this.identity = undefined;
        }
      });
    }
    return this.identity.id;
  }

  private async repo<T>(path: string, init: RequestInit = {}): Promise<T> {
    const url = `${this.repoApi}${path}${path.includes("?") ? "&" : "?"}api-version=${API_VERSION}`;
    try {
      return await requestJson<T>(url, { ...init, headers: await this.headers() });
    } catch (error) {
      if (error instanceof HttpError && error.status === 403) {
        const hint = vscode.l10n.t("Azure DevOps personal access tokens need the Code (Read & Write) scope.");
        throw new HttpError(403, `${error.message} ${hint}`, error.headers);
      }
      throw error;
    }
  }

  private async headers(): Promise<Record<string, string>> {
    return { Authorization: await this.authorization(), Accept: "application/json", "Content-Type": "application/json" };
  }

  private summary(p: AdoPullRequest): PullRequestSummary {
    return {
      id: p.pullRequestId,
      title: p.title,
      author: p.createdBy.displayName,
      updatedAt: p.creationDate,
      url: `${this.webBase}/pullrequest/${p.pullRequestId}`,
    };
  }

  private viewedKey(pr: PullRequest): string {
    return `viewed:${this.repoApi}:${pr.id}`;
  }
}

function post(body: object): RequestInit {
  return { method: "POST", body: JSON.stringify(body) };
}

function latestOf(iterations: ReadonlyMap<number, unknown>): number {
  return Math.max(1, ...iterations.keys());
}

/**
 * Maps a file thread; system messages and pull-request-level threads are not shown (like GitHub review bodies).
 * The positions come tracked to the requested iteration. A thread Azure DevOps could not track there (tracking
 * criteria without an iteration: "tracked if greater than 0") is outdated, and keeps its place in its own iteration.
 */
function toThread(t: AdoThread, iterations: ReadonlyMap<number, string | undefined>): Thread[] {
  const comments = t.comments.filter((c) => !c.isDeleted && c.commentType !== "system" && c.content);
  const context = t.threadContext;
  if (t.isDeleted || !context?.filePath || comments.length === 0) {
    return [];
  }
  const head = Boolean(context.rightFileStart) || !context.leftFileStart;
  const start = head ? context.rightFileStart : context.leftFileStart;
  const end = (head ? context.rightFileEnd : context.leftFileEnd) ?? start;
  const tracking = t.pullRequestThreadContext?.trackingCriteria;
  const lost = Boolean(tracking) && !((tracking?.secondComparingIteration ?? 0) > 0);
  const outdated = !start || lost;
  const sha = iterations.get(t.pullRequestThreadContext?.iterationContext?.secondComparingIteration ?? -1);
  const origStart = tracking?.origRightFileStart ?? start;
  const origEnd = tracking?.origRightFileEnd ?? tracking?.origRightFileStart ?? end;
  const original = lost && head && sha && origStart && origEnd ? { sha, line: origEnd.line, startLine: origStart.line } : undefined;
  return [
    {
      id: String(t.id),
      path: context.filePath.replace(/^\//, ""),
      side: head ? "head" : "base",
      line: outdated ? undefined : end?.line,
      startLine: outdated ? undefined : start?.line,
      isOutdated: outdated,
      ...(original ? { original } : {}),
      isResolved: RESOLVED_STATUSES.has(t.status ?? ""),
      comments: comments.map((c) => ({ id: String(c.id), author: c.author.displayName, body: c.content ?? "", createdAt: c.publishedDate })),
    },
  ];
}
