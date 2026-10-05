import * as vscode from "vscode";
import { requestJson } from "../http";
import type { Draft, PublishResult, PullRequest, PullRequestFilter, PullRequestProvider, PullRequestSummary, Thread, Vote } from "./types";

const API = "https://api.github.com";

interface GhUser {
  login: string;
}
interface GhSearchItem {
  number: number;
  title: string;
  user: GhUser | null;
  updated_at: string;
  html_url: string;
}
interface GhPull extends GhSearchItem {
  body: string | null;
  draft: boolean;
  node_id: string;
  base: { ref: string; sha: string; repo: { full_name: string } };
  /** repo is null when the fork the pull request came from was deleted. */
  head: { ref: string; sha: string; repo: { full_name: string } | null };
}
interface GhThread {
  id: string;
  isResolved: boolean;
  isOutdated: boolean;
  path: string;
  line: number | null;
  startLine: number | null;
  /** Lines in the commit the thread was written against (an outdated thread has no current line). */
  originalLine: number | null;
  originalStartLine: number | null;
  diffSide: "LEFT" | "RIGHT";
  comments: { nodes: { id: string; body: string; createdAt: string; author: GhUser | null; originalCommit: { oid: string } | null }[] };
}
interface Connection<N> {
  pageInfo: { hasNextPage: boolean; endCursor: string | null };
  nodes: N[];
}

const THREADS_QUERY = `query($owner: String!, $name: String!, $number: Int!, $after: String) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      reviewThreads(first: 100, after: $after) {
        pageInfo { hasNextPage endCursor }
        nodes {
          id isResolved isOutdated path line startLine originalLine originalStartLine diffSide
          comments(first: 100) { nodes { id body createdAt author { login } originalCommit { oid } } }
        }
      }
    }
  }
}`;

const FILES_QUERY = `query($owner: String!, $name: String!, $number: Int!, $after: String) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      files(first: 100, after: $after) {
        pageInfo { hasNextPage endCursor }
        nodes { path viewerViewedState }
      }
    }
  }
}`;

const EVENTS: Partial<Record<Vote, string>> = { comment: "COMMENT", approve: "APPROVE", requestChanges: "REQUEST_CHANGES" };

export class GitHubProvider implements PullRequestProvider {
  readonly kind = "github";
  readonly supportedVotes: readonly Vote[] = ["comment", "approve", "requestChanges"];
  readonly commentableLines = "diff";

  constructor(
    private readonly owner: string,
    private readonly repo: string,
    private readonly token: () => Promise<string>,
  ) {}

  async listPullRequests(filter: PullRequestFilter): Promise<PullRequestSummary[]> {
    const qualifier = { assigned: " review-requested:@me", created: " author:@me", all: "" }[filter];
    const q = encodeURIComponent(`repo:${this.owner}/${this.repo} is:pr is:open${qualifier}`);
    // ponytail: first 100 per group, no pagination (spec §5.2)
    const result = await this.rest<{ items: GhSearchItem[] }>(`/search/issues?q=${q}&sort=updated&order=desc&per_page=100`);
    return result.items.map(summary);
  }

  async getPullRequest(id: number): Promise<PullRequest> {
    const p = await this.rest<GhPull>(`/repos/${this.owner}/${this.repo}/pulls/${id}`);
    // A fork's branch is not in this repository: only refs/pull/<id>/head has it.
    const headBranch = p.head.repo?.full_name === p.base.repo.full_name ? p.head.ref : undefined;
    return {
      ...summary(p),
      body: p.body ?? "",
      isDraft: p.draft,
      baseSha: p.base.sha,
      headSha: p.head.sha,
      fetchRefspecs: [`+refs/heads/${p.base.ref}:refs/smart-pr-review/${id}/base`, `+refs/pull/${id}/head:refs/smart-pr-review/${id}/head`],
      checkoutBranch: headBranch ?? `pr/${id}`,
      ...(headBranch === undefined ? {} : { headBranch }),
      nodeId: p.node_id,
    };
  }

  async getThreads(pr: PullRequest): Promise<Thread[]> {
    const nodes = await this.paginate<GhThread>(THREADS_QUERY, pr.id, "reviewThreads");
    return nodes.map((t) => {
      const isOutdated = t.isOutdated || t.line === null;
      // The commit the thread was written against: where an outdated head-side thread can still be shown.
      const sha = t.comments.nodes[0]?.originalCommit?.oid;
      const original =
        isOutdated && t.diffSide === "RIGHT" && sha && t.originalLine
          ? { sha, line: t.originalLine, ...(t.originalStartLine ? { startLine: t.originalStartLine } : {}) }
          : undefined;
      return {
        id: t.id,
        path: t.path,
        side: t.diffSide === "LEFT" ? "base" : "head",
        line: t.line ?? undefined,
        startLine: t.startLine ?? undefined,
        isOutdated,
        ...(original ? { original } : {}),
        isResolved: t.isResolved,
        // ponytail: first 100 comments per thread; paginate comments if longer threads show up
        comments: t.comments.nodes.map((c) => ({ id: c.id, author: c.author?.login ?? "ghost", body: c.body, createdAt: c.createdAt })),
      };
    });
  }

  async reply(_pr: PullRequest, threadId: string, body: string): Promise<void> {
    await this.graphql(
      "mutation($id: ID!, $body: String!) { addPullRequestReviewThreadReply(input: { pullRequestReviewThreadId: $id, body: $body }) { comment { id } } }",
      { id: threadId, body },
    );
  }

  async setThreadResolved(_pr: PullRequest, threadId: string, resolved: boolean): Promise<void> {
    const mutation = resolved ? "resolveReviewThread" : "unresolveReviewThread";
    await this.graphql(`mutation($id: ID!) { ${mutation}(input: { threadId: $id }) { thread { id } } }`, { id: threadId });
  }

  async getViewedFiles(pr: PullRequest): Promise<Set<string>> {
    const files = await this.paginate<{ path: string; viewerViewedState: string }>(FILES_QUERY, pr.id, "files");
    return new Set(files.filter((f) => f.viewerViewedState === "VIEWED").map((f) => f.path));
  }

  async setFileViewed(pr: PullRequest, path: string, viewed: boolean): Promise<void> {
    const mutation = viewed ? "markFileAsViewed" : "unmarkFileAsViewed";
    await this.graphql(`mutation($id: ID!, $path: String!) { ${mutation}(input: { pullRequestId: $id, path: $path }) { clientMutationId } }`, {
      id: pr.nodeId,
      path,
    });
  }

  async publishReview(pr: PullRequest, body: string, drafts: readonly Draft[], vote: Vote): Promise<PublishResult> {
    const event = EVENTS[vote];
    if (!event) {
      throw new Error(vscode.l10n.t('GitHub does not support the vote "{0}".', vote));
    }
    // GitHub requires the review body for COMMENT and REQUEST_CHANGES.
    if (!body.trim() && (vote === "requestChanges" || vote === "comment")) {
      throw new Error(vscode.l10n.t("Write a review summary for this vote."));
    }
    await this.rest(`/repos/${this.owner}/${this.repo}/pulls/${pr.id}/reviews`, {
      method: "POST",
      body: JSON.stringify({
        commit_id: pr.headSha,
        event,
        ...(body.trim() ? { body } : {}),
        comments: drafts.map((d) => ({
          path: d.path,
          body: d.body,
          side: "RIGHT",
          line: d.endLine,
          ...(d.startLine < d.endLine ? { start_line: d.startLine, start_side: "RIGHT" } : {}),
        })),
      }),
    });
    // GitHub creates the review atomically: every draft is published, or the call above throws.
    return { published: drafts.map((d) => d.id), failed: [] };
  }

  private async paginate<N>(query: string, number: number, field: "reviewThreads" | "files"): Promise<N[]> {
    const nodes: N[] = [];
    let after: string | null = null;
    do {
      const data: { repository: { pullRequest: Record<string, Connection<N>> } } = await this.graphql(query, {
        owner: this.owner,
        name: this.repo,
        number,
        after,
      });
      const page = data.repository.pullRequest[field];
      nodes.push(...page.nodes);
      after = page.pageInfo.hasNextPage ? page.pageInfo.endCursor : null;
    } while (after);
    return nodes;
  }

  private async rest<T>(path: string, init: RequestInit = {}): Promise<T> {
    return requestJson<T>(`${API}${path}`, { ...init, headers: await this.headers() });
  }

  private async graphql<T>(query: string, variables: Record<string, unknown>): Promise<T> {
    const result = await this.rest<{ data?: T; errors?: { message: string }[] }>("/graphql", {
      method: "POST",
      body: JSON.stringify({ query, variables }),
    });
    if (result.errors?.length) {
      throw new Error(result.errors.map((e) => e.message).join("; "));
    }
    return result.data as T;
  }

  private async headers(): Promise<Record<string, string>> {
    return {
      Authorization: `Bearer ${await this.token()}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "Content-Type": "application/json",
    };
  }
}

function summary(item: GhSearchItem): PullRequestSummary {
  return { id: item.number, title: item.title, author: item.user?.login ?? "ghost", updatedAt: item.updated_at, url: item.html_url };
}
