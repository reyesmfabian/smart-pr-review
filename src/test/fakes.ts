import * as vscode from "vscode";
import type { ChatModel } from "../ai/engines";
import type { Draft, PublishResult, PullRequest, PullRequestProvider, Thread, Vote } from "../providers/types";
import type { TestRepo } from "./repo";

export function memento(): vscode.Memento {
  const data = new Map<string, unknown>();
  return {
    keys: () => [...data.keys()],
    get: (key: string, fallback?: unknown) => (data.has(key) ? data.get(key) : fallback),
    update: async (key: string, value: unknown) => {
      data.set(key, value);
    },
  } as vscode.Memento;
}

/** In-memory provider for the pull request created by makeRepo(). */
export class FakeProvider implements PullRequestProvider {
  readonly kind = "github";
  readonly supportedVotes: readonly Vote[] = ["comment", "approve"];
  commentableLines: "diff" | "file" = "diff";
  threads: Thread[] = [];
  viewed = new Set<string>();
  published: { body: string; drafts: readonly Draft[]; vote: Vote }[] = [];
  /** Draft ids whose publication fails. */
  failing = new Set<string>();
  /** When set, publishReview throws it (e.g. GitHub rejecting the whole review). */
  publishError: Error | undefined;
  headSha: string;
  /** The branch the pull request comes from, when the test pushed one to the remote; unset, it is a fork's. */
  headBranch: string | undefined;

  constructor(private readonly repo: TestRepo) {
    this.headSha = repo.headSha;
  }

  async listPullRequests() {
    return [{ id: 1, title: "Test PR", author: "ana", updatedAt: "", url: "https://example.com/pr/1" }];
  }

  async getPullRequest(id: number): Promise<PullRequest> {
    return {
      id,
      title: "Test PR",
      author: "ana",
      updatedAt: "",
      url: "https://example.com/pr/1",
      body: "Body",
      isDraft: false,
      baseSha: this.repo.baseSha,
      headSha: this.headSha,
      fetchRefspecs: ["+refs/heads/main:refs/smart-pr-review/1/base", "+refs/pull/1/head:refs/smart-pr-review/1/head"],
      checkoutBranch: this.headBranch ?? "pr/1",
      headBranch: this.headBranch,
      nodeId: "PR_1",
    };
  }

  async getThreads() {
    return this.threads;
  }

  async reply() {}

  async setThreadResolved(_pr: PullRequest, _threadId: string, _resolved: boolean) {
    void [_pr, _threadId, _resolved];
  }

  async getViewedFiles() {
    return new Set(this.viewed);
  }

  async setFileViewed(_pr: PullRequest, path: string, viewed: boolean) {
    if (viewed) {
      this.viewed.add(path);
    } else {
      this.viewed.delete(path);
    }
  }

  async publishReview(_pr: PullRequest, body: string, drafts: readonly Draft[], vote: Vote): Promise<PublishResult> {
    if (this.publishError) {
      throw this.publishError;
    }
    this.published.push({ body, drafts, vote });
    return {
      published: drafts.filter((d) => !this.failing.has(d.id)).map((d) => d.id),
      failed: drafts.filter((d) => this.failing.has(d.id)).map((d) => ({ id: d.id, error: "boom" })),
    };
  }
}

/** Chat model stub: answers `answer` and records the prompts it got. */
export function fakeModel(answer: string, maxInputTokens = 1_000_000) {
  const prompts: string[] = [];
  const justifications: (string | undefined)[] = [];
  const model: ChatModel = {
    id: "fake-id",
    name: "fake",
    maxInputTokens,
    countTokens: async (text) => text.length,
    sendRequest: async (messages, options) => {
      prompts.push(messages.map((m) => m.content.map((p) => (p instanceof vscode.LanguageModelTextPart ? p.value : "")).join("")).join("\n"));
      justifications.push(options?.justification);
      return {
        text: (async function* () {
          yield answer.slice(0, 5);
          yield answer.slice(5);
        })(),
      };
    },
  };
  return { model, prompts, justifications };
}
