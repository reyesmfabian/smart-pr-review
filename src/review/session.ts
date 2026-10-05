import * as vscode from "vscode";
import type { ChangedFile, LineRange } from "../git";
import * as git from "../git";
import type { Draft, DraftOrigin, PublishResult, PullRequest, PullRequestProvider, Thread, Vote } from "../providers/types";
import { DraftStore } from "./drafts";

export interface OpenPullRequest {
  pr: PullRequest;
  mergeBase: string;
  files: ChangedFile[];
  threads: Thread[];
  viewed: Set<string>;
  drafts: DraftStore;
  /** Whether the working tree HEAD is the pull request head. */
  checkedOut: boolean;
}

/** The pull request being reviewed. Views subscribe to onDidChange. */
export class ReviewSession implements vscode.Disposable {
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChange = this.changed.event;
  current: OpenPullRequest | undefined;
  /** Bumped by every open(): a slower, older open must not replace the pull request clicked last. */
  private opening = 0;

  constructor(
    readonly provider: PullRequestProvider,
    readonly repoRoot: string,
    private readonly remote: string,
    private readonly storage: vscode.Memento,
    /** Identifies the repository in storage keys, e.g. "github:octo/app". */
    private readonly repoKey: string,
  ) {}

  require(): OpenPullRequest {
    if (!this.current) {
      throw new Error(vscode.l10n.t("Open a pull request first."));
    }
    return this.current;
  }

  async open(id: number): Promise<void> {
    const ticket = ++this.opening;
    const pr = await this.provider.getPullRequest(id);
    await git.fetchRefs(this.repoRoot, this.remote, pr.fetchRefspecs);
    if (pr.headFetch) {
      await git.fetchRefs(this.repoRoot, safeRepositoryUrl(pr.headFetch.url), [pr.headFetch.refspec]);
    }
    for (const sha of [pr.baseSha, pr.headSha]) {
      if (!(await git.hasCommit(this.repoRoot, sha))) {
        throw new Error(vscode.l10n.t("Commit {0} is not available after fetching the pull request.", sha.slice(0, 8)));
      }
    }
    const mergeBase = await git.mergeBase(this.repoRoot, pr.baseSha, pr.headSha);
    const [files, threads, viewed, head] = await Promise.all([
      git.changedFiles(this.repoRoot, mergeBase, pr.headSha),
      this.provider.getThreads(pr),
      this.provider.getViewedFiles(pr),
      git.headSha(this.repoRoot),
    ]);
    if (ticket !== this.opening) {
      return;
    }
    const drafts = new DraftStore(this.storage, `${this.repoKey}:${pr.id}`);
    this.current = { pr, mergeBase, files, threads, viewed, drafts, checkedOut: head === pr.headSha };
    this.changed.fire();
  }

  /** Leaves the review: views, comments and the gutter "+" go away; drafts stay stored for the next open. */
  close(): void {
    // An open still in flight must not bring the pull request back.
    this.opening++;
    this.current = undefined;
    this.changed.fire();
  }

  async refresh(): Promise<void> {
    await this.open(this.require().pr.id);
  }

  /** `confirmReset` is asked before discarding commits only the local branch has (after a force-push). */
  async checkout(confirmReset: (branch: string) => Promise<boolean>): Promise<void> {
    const c = this.require();
    if (await git.isDirty(this.repoRoot)) {
      throw new Error(vscode.l10n.t("You have uncommitted changes. Commit or stash them before checking out the pull request."));
    }
    const { checkoutBranch: branch, headBranch, headSha } = c.pr;
    const reset = !(await git.canFastForward(this.repoRoot, branch, headSha));
    if (reset && !(await confirmReset(branch))) {
      return;
    }
    if (headBranch) {
      // The remote-tracking branch to follow: open() fetched the pull request into refs/smart-pr-review only.
      await git.fetchRefs(this.repoRoot, this.remote, [`+refs/heads/${headBranch}:refs/remotes/${this.remote}/${headBranch}`]);
    }
    await git.checkout(this.repoRoot, branch, headSha, reset, headBranch && `${this.remote}/${headBranch}`);
    c.checkedOut = true;
    this.changed.fire();
  }

  /** Head-side lines of `path` that accept comments (1-based), or 'all'. */
  async commentableRanges(path: string): Promise<LineRange[] | "all"> {
    const c = this.require();
    const file = c.files.find((f) => f.path === path);
    if (!file || file.status === "D") {
      return [];
    }
    if (this.provider.commentableLines === "file") {
      return "all";
    }
    return git.diffRanges(this.repoRoot, c.mergeBase, c.pr.headSha, file);
  }

  async reply(threadId: string, body: string): Promise<void> {
    await this.provider.reply(this.require().pr, threadId, body);
    await this.reloadThreads();
  }

  async setResolved(threadId: string, resolved: boolean): Promise<void> {
    await this.provider.setThreadResolved(this.require().pr, threadId, resolved);
    await this.reloadThreads();
  }

  async setViewed(path: string, viewed: boolean): Promise<void> {
    const c = this.require();
    try {
      await this.provider.setFileViewed(c.pr, path, viewed);
      if (viewed) {
        c.viewed.add(path);
      } else {
        c.viewed.delete(path);
      }
    } finally {
      // Also on failure: the tree already toggled the checkbox and must show the real state again.
      this.changed.fire();
    }
  }

  async addDraft(
    path: string,
    startLine: number,
    endLine: number,
    body: string,
    origin: DraftOrigin = "manual",
    columns?: Pick<Draft, "startColumn" | "endColumn">,
  ): Promise<Draft> {
    const c = this.require();
    const draft = await c.drafts.add({ path, startLine, endLine, ...columns, body, headSha: c.pr.headSha, origin });
    this.changed.fire();
    return draft;
  }

  /** Adds a section to the review summary, after what is already there. */
  async appendSummary(text: string): Promise<void> {
    const drafts = this.require().drafts;
    const current = drafts.summary().trim();
    await drafts.setSummary(current ? `${current}\n\n${text}` : text);
    this.changed.fire();
  }

  async updateDraft(id: string, body: string): Promise<void> {
    await this.require().drafts.update(id, body);
    this.changed.fire();
  }

  async removeDrafts(ids: readonly string[]): Promise<void> {
    await this.require().drafts.remove(ids);
    this.changed.fire();
  }

  /**
   * Publishes every draft with the summary and vote. Returns undefined when the user declines to
   * publish drafts written against an older head. Drafts are removed only once the provider confirms them.
   */
  async publish(body: string, vote: Vote, confirmStale: (count: number) => Promise<boolean>): Promise<PublishResult | undefined> {
    const c = this.require();
    await c.drafts.setSummary(body);
    const drafts = c.drafts.all();
    const latest = await this.provider.getPullRequest(c.pr.id);
    const stale = drafts.filter((d) => d.headSha !== latest.headSha).length;
    if (stale > 0 && !(await confirmStale(stale))) {
      return undefined;
    }
    const result = await this.provider.publishReview(latest, body, drafts, vote);
    await c.drafts.remove(result.published);
    if (result.failed.length === 0) {
      await c.drafts.setSummary("");
    }
    await this.reloadThreads();
    return result;
  }

  dispose(): void {
    this.changed.dispose();
  }

  private async reloadThreads(): Promise<void> {
    const c = this.require();
    c.threads = await this.provider.getThreads(c.pr);
    this.changed.fire();
  }
}

/** The URL becomes a git argument: only http(s), ssh, file and user@host:path URLs; never an option like "--upload-pack=…". */
function safeRepositoryUrl(url: string): string {
  if (/^(https?|ssh|file):\/\//i.test(url) || /^[\w.-]+@[\w.-]+:[^-]/.test(url)) {
    return url;
  }
  throw new Error(vscode.l10n.t("Unsupported repository URL: {0}", url));
}
