import type { ProviderKind } from "../remote";

export type PullRequestFilter = "assigned" | "created" | "all";

export type Vote = "comment" | "approve" | "approveWithSuggestions" | "requestChanges" | "waitForAuthor" | "reject";

export interface PullRequestSummary {
  id: number;
  title: string;
  author: string;
  updatedAt: string;
  url: string;
}

export interface PullRequest extends PullRequestSummary {
  body: string;
  isDraft: boolean;
  baseSha: string;
  headSha: string;
  /** Refspecs, fetched from the pull request remote, that make baseSha and headSha available locally. */
  fetchRefspecs: string[];
  /** Head commits fetched from another repository instead (Azure DevOps forks). */
  headFetch?: { url: string; refspec: string };
  /** Local branch created by "Checkout Pull Request": headBranch when there is one, else pr/<id>. */
  checkoutBranch: string;
  /** The pull request's branch in this repository, which the checkout tracks; absent for a fork's branch. */
  headBranch?: string;
  /** Provider-specific id some APIs need (GitHub GraphQL node id). */
  nodeId: string;
}

export interface ThreadComment {
  id: string;
  author: string;
  body: string;
  createdAt: string;
}

export interface Thread {
  id: string;
  path: string;
  /** Diff side the thread is anchored to. */
  side: "base" | "head";
  /** 1-based. Undefined when the thread is outdated. */
  line?: number;
  startLine?: number;
  isOutdated: boolean;
  /** Where an outdated head-side thread was written: the commit of the time and its 1-based lines there. */
  original?: { sha: string; line: number; startLine?: number };
  isResolved: boolean;
  comments: ThreadComment[];
}

export type DraftOrigin = "manual" | { profile: string; engine: string; severity: "error" | "warning" | "info" };

export interface Draft {
  id: string;
  path: string;
  /** 1-based, head side. */
  startLine: number;
  endLine: number;
  /** 1-based columns of the text the reviewer selected (end exclusive); absent when the draft is on whole lines. */
  startColumn?: number;
  endColumn?: number;
  body: string;
  /** Pull request head when the draft was written. */
  headSha: string;
  origin: DraftOrigin;
}

/** Ids are draft ids, or 'vote' / 'summary' for the review-level parts a provider sends separately. */
export interface PublishResult {
  published: string[];
  failed: { id: string; error: string }[];
}

export interface PullRequestProvider {
  readonly kind: ProviderKind;
  readonly supportedVotes: readonly Vote[];
  /** 'diff': only lines inside diff hunks accept comments (GitHub). 'file': any head line (Azure DevOps). */
  readonly commentableLines: "diff" | "file";
  listPullRequests(filter: PullRequestFilter): Promise<PullRequestSummary[]>;
  getPullRequest(id: number): Promise<PullRequest>;
  getThreads(pr: PullRequest): Promise<Thread[]>;
  reply(pr: PullRequest, threadId: string, body: string): Promise<void>;
  setThreadResolved(pr: PullRequest, threadId: string, resolved: boolean): Promise<void>;
  publishReview(pr: PullRequest, body: string, drafts: readonly Draft[], vote: Vote): Promise<PublishResult>;
  getViewedFiles(pr: PullRequest): Promise<Set<string>>;
  setFileViewed(pr: PullRequest, path: string, viewed: boolean): Promise<void>;
}
