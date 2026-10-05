import { createHash, randomBytes } from "node:crypto";
import * as vscode from "vscode";
import { describeError } from "../http";
import type { Draft, Vote } from "../providers/types";
import { openFileDiff } from "./diff";
import type { OpenPullRequest, ReviewSession } from "./session";

const VOTES: Record<Vote, () => string> = {
  comment: () => vscode.l10n.t("Comment"),
  approve: () => vscode.l10n.t("Approve"),
  approveWithSuggestions: () => vscode.l10n.t("Approve with suggestions"),
  requestChanges: () => vscode.l10n.t("Request changes"),
  waitForAuthor: () => vscode.l10n.t("Wait for author"),
  reject: () => vscode.l10n.t("Reject"),
};

/** Votes that approve the pull request, hidden when smartPrReview.allowApproval is off. */
const APPROVING: readonly Vote[] = ["approve", "approveWithSuggestions"];

export function allowedVotes(supported: readonly Vote[], allowApproval: boolean): readonly Vote[] {
  return allowApproval ? supported : supported.filter((vote) => !APPROVING.includes(vote));
}

export interface PublishView {
  cspSource: string;
  nonce: string;
  title: string;
  url: string;
  author: string;
  /** Rendered by VS Code's markdown renderer; scripts are blocked by the CSP. */
  descriptionHtml: string;
  summary: string;
  draftCount: number;
  drafts?: readonly Draft[];
  revision?: string;
  votes: readonly Vote[];
  /** The vote shown as selected: the last one the reviewer picked. */
  vote: Vote;
}

export type PanelMessage =
  | { type: "summary"; text: string }
  | { type: "vote"; vote: Vote }
  | { type: "publish"; text: string; vote: Vote; revision: string }
  | { type: "openDraft"; id: string }
  | { type: "reviewWithAI" };

/** Validates a message posted by the webview (it also renders remote HTML, so treat it as untrusted). */
export function parseMessage(raw: unknown, votes: readonly Vote[]): PanelMessage | undefined {
  if (typeof raw !== "object" || raw === null) {
    return undefined;
  }
  const m = raw as Record<string, unknown>;
  if (m.type === "openDraft" && typeof m.id === "string") {
    return { type: "openDraft", id: m.id };
  }
  if (m.type === "reviewWithAI") {
    return { type: "reviewWithAI" };
  }
  if (m.type === "vote") {
    return votes.includes(m.vote as Vote) ? { type: "vote", vote: m.vote as Vote } : undefined;
  }
  if (typeof m.text !== "string") {
    return undefined;
  }
  if (m.type === "summary") {
    return { type: "summary", text: m.text };
  }
  if (m.type === "publish" && typeof m.revision === "string" && votes.includes(m.vote as Vote)) {
    return { type: "publish", text: m.text, vote: m.vote as Vote, revision: m.revision };
  }
  return undefined;
}

/**
 * The pull request's description comes last in the document, after the controls and the script that
 * looks them up: an element of it with their id (a hidden <textarea id="summary">) can never be the one
 * the script reads. It is collapsible and secondary to the publication preview.
 */
export function renderPublishHtml(v: PublishView): string {
  const options = v.votes
    .map((vote) => `<option value="${vote}"${vote === v.vote ? " selected" : ""}>${escapeHtml(VOTES[vote]())}</option>`)
    .join("");
  const drafts = (v.drafts ?? [])
    .map((d) => {
      const place = d.startLine === d.endLine ? `${d.path}:${d.startLine}` : `${d.path}:${d.startLine}-${d.endLine}`;
      const origin =
        d.origin === "manual"
          ? vscode.l10n.t("Manual")
          : `${d.origin.profile} · ${{ error: vscode.l10n.t("Error"), warning: vscode.l10n.t("Warning"), info: vscode.l10n.t("Info") }[d.origin.severity]}`;
      return `<li><div class="draft-heading"><strong>${escapeHtml(place)}</strong><span>${escapeHtml(origin)}</span></div>
      <pre>${escapeHtml(d.body)}</pre><button class="secondary open-draft" data-draft="${escapeHtml(d.id)}">${escapeHtml(vscode.l10n.t("Open in diff"))}</button></li>`;
    })
    .join("");
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; form-action 'none'; base-uri 'none'; img-src ${v.cspSource} https: data:; style-src 'nonce-${v.nonce}'; script-src 'nonce-${v.nonce}';">
<style nonce="${v.nonce}">
  body { max-width: 900px; margin: 0 auto; font-size: var(--vscode-font-size); font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 0 16px 16px; display: flex; flex-direction: column; }
  header { padding-top: 8px; }
  h2 { font-size: 18px; margin: 12px 0 6px; overflow-wrap: anywhere; }
  h3 { font-size: 14px; margin: 20px 0 8px; }
  p { line-height: 1.5; }
  header p, .hint, .draft-heading span { color: var(--vscode-descriptionForeground); font-size: 12px; }
  a { color: var(--vscode-textLink-foreground); }
  :focus-visible { outline: 1px solid var(--vscode-focusBorder); outline-offset: 2px; }
  .preview { padding: 0; list-style: none; }
  .preview li { border: 1px solid var(--vscode-panel-border); padding: 12px; margin: 8px 0; border-radius: 3px; }
  .draft-heading { display: flex; gap: 8px; justify-content: space-between; flex-wrap: wrap; overflow-wrap: anywhere; }
  pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.5; }
  .publish-actions { position: sticky; bottom: 0; background: var(--vscode-editor-background); border-top: 1px solid var(--vscode-panel-border); padding: 12px 0; }
  summary { cursor: pointer; }
  button { font: inherit; border-radius: 2px; }
  textarea, select { width: 100%; box-sizing: border-box; margin: 4px 0 12px; font: inherit; color: var(--vscode-input-foreground); background: var(--vscode-input-background); border: 1px solid var(--vscode-input-border, transparent); }
  button { color: var(--vscode-button-foreground); background: var(--vscode-button-background); border: none; padding: 6px 14px; cursor: pointer; }
  button:hover { background: var(--vscode-button-hoverBackground); }
  button:disabled { opacity: 0.6; cursor: default; }
  button.secondary { color: var(--vscode-button-secondaryForeground); background: var(--vscode-button-secondaryBackground); }
  button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
  .description { border-top: 1px solid var(--vscode-panel-border); padding-top: 12px; margin-top: 16px; overflow-wrap: anywhere; }
</style>
</head>
<body>
<header>
<h2>${escapeHtml(v.title)}</h2>
<p>${escapeHtml(v.author)} · <a href="${escapeHtml(v.url)}">${escapeHtml(vscode.l10n.t("Open in Browser"))}</a></p>
<p>${escapeHtml(vscode.l10n.t("Preview your review before publishing. Nothing is sent until you choose Publish now."))}</p>
</header>
<div>
<h3>${escapeHtml(vscode.l10n.t("Comments to publish"))} (${v.draftCount})</h3>
<ul class="preview">${drafts}</ul>
${v.draftCount === 0 ? `<p class="hint">${escapeHtml(vscode.l10n.t("No draft comments. Only your summary and vote will be sent."))}</p>` : ""}
<h3>${escapeHtml(vscode.l10n.t("Review decision"))}</h3>
<label for="summary">${escapeHtml(vscode.l10n.t("Review summary"))}</label>
<textarea id="summary" rows="6">${escapeHtml(v.summary)}</textarea>
<label for="vote">${escapeHtml(vscode.l10n.t("Vote"))}</label>
<select id="vote">${options}</select>
<p>${escapeHtml(vscode.l10n.t("{0} draft comment(s) will be published.", v.draftCount))}</p>
<div class="publish-actions"><button id="publish">${escapeHtml(vscode.l10n.t("Publish now"))}</button></div>
</div>
<script nonce="${v.nonce}">
  const vscode = acquireVsCodeApi();
  const summary = document.getElementById('summary');
  const publish = document.getElementById('publish');
  const vote = document.getElementById('vote');
  summary.addEventListener('input', () => vscode.postMessage({ type: 'summary', text: summary.value }));
  vote.addEventListener('change', () => vscode.postMessage({ type: 'vote', vote: vote.value }));
  document.querySelectorAll('.open-draft').forEach(button => button.addEventListener('click', () => vscode.postMessage({ type: 'openDraft', id: button.dataset.draft })));
  const revision = ${JSON.stringify(v.revision ?? "").replace(/</g, "\\u003c")};
  publish.addEventListener('click', () => {
    publish.disabled = true;
    summary.disabled = true;
    vote.disabled = true;
    vscode.postMessage({ type: 'publish', text: summary.value, vote: vote.value, revision });
  });
</script>
<section class="description"><details><summary>${escapeHtml(vscode.l10n.t("Pull request details"))}</summary>${v.descriptionHtml}</details></section>
</body>
</html>`;
}

function escapeHtml(text: string): string {
  const entities: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
  return text.replace(/[&<>"']/g, (ch) => entities[ch]);
}

async function confirmStale(count: number): Promise<boolean> {
  const publish = vscode.l10n.t("Publish");
  const message = vscode.l10n.t(
    "The pull request has new commits since {0} of your drafts were written. Their lines may have moved. Publish anyway?",
    count,
  );
  return (await vscode.window.showWarningMessage(message, { modal: true }, publish)) === publish;
}

/** Bind the preview to the exact comments, not just their count (AI may add drafts while it is open). */
export function publishRevision(c: OpenPullRequest): string {
  return createHash("sha256")
    .update(JSON.stringify([c.pr.id, c.pr.headSha, c.drafts.all()]))
    .digest("hex");
}

export class PublishPanel implements vscode.Disposable {
  private static instance: PublishPanel | undefined;
  private readonly disposables: vscode.Disposable[] = [];
  /** Kept across re-renders: losing it would turn a retried "Approve" into a plain comment. */
  private vote: Vote;
  private disposed = false;
  private publishing = false;
  private renderTicket = 0;
  /** The pull request the page shows: a click on it must not act on another one opened since. */
  private shown: number | undefined;

  static show(session: ReviewSession, column: vscode.ViewColumn = vscode.ViewColumn.Active): void {
    session.require();
    if (PublishPanel.instance) {
      PublishPanel.instance.panel.reveal();
      return;
    }
    const panel = vscode.window.createWebviewPanel("smartPrReview.publish", vscode.l10n.t("Prepare review"), column, {
      enableScripts: true,
      retainContextWhenHidden: true,
      localResourceRoots: [],
    });
    PublishPanel.instance = new PublishPanel(panel, session);
  }

  private constructor(
    private readonly panel: vscode.WebviewPanel,
    private readonly session: ReviewSession,
  ) {
    this.vote = this.votes()[0];
    this.disposables.push(
      panel.webview.onDidReceiveMessage((raw) => this.onMessage(raw)),
      session.onDidChange(() => void this.update()),
      panel.onDidDispose(() => this.dispose()),
      vscode.workspace.onDidChangeConfiguration((e) => e.affectsConfiguration("smartPrReview.allowApproval") && void this.update()),
    );
    void this.update();
  }

  /** The provider's votes, without the approving ones when the user turned approval off. */
  private votes(): readonly Vote[] {
    const allow = vscode.workspace.getConfiguration("smartPrReview", vscode.Uri.file(this.session.repoRoot)).get<boolean>("allowApproval", false);
    return allowedVotes(this.session.provider.supportedVotes, allow);
  }

  dispose(): void {
    this.disposed = true;
    PublishPanel.instance = undefined;
    this.disposables.forEach((d) => d.dispose());
  }

  /** Refresh the preview when drafts change; never replace the form during publication. */
  private async update(): Promise<void> {
    if (this.publishing || this.disposed) {
      return;
    }
    const ticket = ++this.renderTicket;
    const c = this.session.current;
    if (!c) {
      // The review was exited.
      this.panel.dispose();
      return;
    }
    let descriptionHtml: string;
    try {
      descriptionHtml = await vscode.commands.executeCommand<string>("markdown.api.render", c.pr.body);
    } catch {
      descriptionHtml = `<pre>${escapeHtml(c.pr.body)}</pre>`;
    }
    // The panel may have closed while the description rendered (it closes right after a successful publish),
    // or another pull request opened: its own update renders it, and this one must not land after it.
    if (this.disposed || this.publishing || ticket !== this.renderTicket || this.session.current !== c) {
      return;
    }
    const votes = this.votes();
    this.shown = c.pr.id;
    this.panel.title = vscode.l10n.t("Review #{0}", c.pr.id);
    this.panel.webview.html = renderPublishHtml({
      cspSource: this.panel.webview.cspSource,
      nonce: randomBytes(16).toString("base64"),
      title: `#${c.pr.id} ${c.pr.title}`,
      url: c.pr.url,
      author: c.pr.author,
      descriptionHtml,
      summary: c.drafts.summary(),
      draftCount: c.drafts.all().length,
      drafts: c.drafts.all(),
      revision: publishRevision(c),
      votes,
      vote: votes.includes(this.vote) ? this.vote : votes[0],
    });
  }

  private async onMessage(raw: unknown): Promise<void> {
    if (this.publishing || this.disposed) {
      return;
    }
    const c = this.session.current;
    const message = c && parseMessage(raw, this.votes());
    if (!c || !message || c.pr.id !== this.shown) {
      return;
    }
    if (message.type === "openDraft") {
      const draft = c.drafts.all().find((d) => d.id === message.id);
      const file = draft && c.files.find((f) => f.path === draft.path);
      if (draft && file) {
        await openFileDiff(this.session, file, draft.startLine, this.panel.viewColumn);
      }
      return;
    }
    if (message.type === "summary") {
      await c.drafts.setSummary(message.text);
      return;
    }
    if (message.type === "reviewWithAI") {
      await vscode.commands.executeCommand("smartPrReview.reviewWithAI");
      return;
    }
    this.vote = message.vote;
    if (message.type === "vote") {
      return;
    }
    if (message.revision !== publishRevision(c)) {
      void vscode.window.showWarningMessage(vscode.l10n.t("The drafts changed. Check the updated preview before publishing."));
      await this.update();
      return;
    }
    this.publishing = true;
    try {
      const result = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: vscode.l10n.t("Publishing review…") },
        () => this.session.publish(message.text, message.vote, confirmStale),
      );
      if (result?.failed.length) {
        const errors = result.failed.map((f) => f.error).join("; ");
        void vscode.window.showErrorMessage(
          vscode.l10n.t("{0} item(s) could not be published and were kept to retry: {1}", result.failed.length, errors),
        );
      } else if (result) {
        void vscode.window.showInformationMessage(vscode.l10n.t("Review published."));
        this.panel.dispose();
        return;
      }
    } catch (error) {
      void vscode.window.showErrorMessage(describeError(error));
    } finally {
      this.publishing = false;
    }
    await this.update();
  }
}
