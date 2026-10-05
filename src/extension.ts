import { join } from "node:path";
import * as vscode from "vscode";
import { createProfile, reviewWithAi } from "./ai/commands";
import { cleanupWorktrees } from "./ai/runReview";
import { type Auth, AzureDevOpsAuth, githubAuth, httpConsent, recoveryActions } from "./auth";
import type { ChangedFile } from "./git";
import * as git from "./git";
import { describeError } from "./http";
import { log } from "./log";
import { AzureDevOpsProvider } from "./providers/azureDevOps";
import { GitHubProvider } from "./providers/github";
import type { PullRequestProvider, Thread } from "./providers/types";
import { parseRemote, type RemoteInfo } from "./remote";
import { CommentsManager, type DraftComment } from "./review/comments";
import { closeReviewTabs, openFileDiff, openThread, RevisionContentProvider, SCHEME } from "./review/diff";
import { browserUri, CurrentPullRequestProvider, PullRequestListProvider, searchPullRequests } from "./review/prTree";
import { PublishPanel } from "./review/publishPanel";
import { ReviewWindow } from "./review/reviewWindow";
import { ReviewSession } from "./review/session";

type State = "noRepository" | "unsupported" | "signedOut" | "ready";

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  context.subscriptions.push(log);
  const repository = await findRepository();
  if (!repository) {
    return setState("noRepository");
  }
  const { root, remoteName, remote } = repository;
  if (!remote) {
    return setState("unsupported");
  }
  // Worktrees of AI reviews interrupted by closing VS Code.
  void cleanupWorktrees(root, join(context.globalStorageUri.fsPath, "worktrees"), 24 * 60 * 60 * 1000);

  const ado =
    remote.kind === "azureDevOps"
      ? new AzureDevOpsAuth(
          remote.host,
          context.secrets,
          undefined,
          remote.origin.startsWith("http:") ? httpConsent(remote.origin, context.globalState) : undefined,
        )
      : undefined;
  const auth: Auth = ado ?? githubAuth;
  const provider: PullRequestProvider = ado
    ? new AzureDevOpsProvider(remote, () => ado.authorization(), context.workspaceState)
    : new GitHubProvider(remote.owner, remote.repo, githubAuth.token);
  const repoKey = ado ? `azureDevOps:${remote.host}/${remote.owner}/${remote.project}/${remote.repo}` : `github:${remote.owner}/${remote.repo}`;
  await vscode.commands.executeCommand("setContext", "smartPrReview.provider", ado ? (ado.cloud ? "azureDevOps" : "azureDevOpsServer") : "github");

  const run = (task: () => Promise<unknown>) => runTask(task, auth);
  const command = <A extends unknown[]>(id: string, handler: (...args: A) => unknown) =>
    vscode.commands.registerCommand(id, (...args) => run(async () => handler(...(args as A))));

  const session = new ReviewSession(provider, root, remoteName, context.workspaceState, repoKey);
  const list: PullRequestListProvider = new PullRequestListProvider(
    provider,
    () => auth.isSignedIn(),
    (error) => {
      void report(error, auth, async () => list.refresh());
    },
  );
  const comments = new CommentsManager(session);
  const listView = vscode.window.createTreeView("smartPrReview.pullRequests", { treeDataProvider: list });
  const currentView = vscode.window.createTreeView("smartPrReview.currentPullRequest", {
    treeDataProvider: new CurrentPullRequestProvider(session),
    manageCheckboxStateManually: true,
  });
  currentView.message = vscode.l10n.t("Select a pull request to review.");

  const updateState = async () => {
    await setState((await auth.isSignedIn()) ? "ready" : "signedOut");
    list.refresh();
  };

  context.subscriptions.push(
    session,
    comments,
    listView,
    currentView,
    vscode.workspace.registerTextDocumentContentProvider(SCHEME, new RevisionContentProvider(root)),
    session.onDidChange(() => {
      const c = session.current;
      currentView.message = undefined;
      currentView.description = c ? `#${c.pr.id} ${c.pr.title}` : undefined;
      void vscode.commands.executeCommand("setContext", "smartPrReview.hasPullRequest", c !== undefined);
      void vscode.commands.executeCommand("setContext", "smartPrReview.checkedOut", c?.checkedOut ?? false);
    }),
    currentView.onDidChangeCheckboxState(async (e) => {
      for (const [node, state] of e.items) {
        if (node.kind === "file") {
          await run(() => session.setViewed(node.file.path, state === vscode.TreeItemCheckboxState.Checked));
        }
      }
    }),
    // Sign-ins of this provider's account, or a stored token, may change who we are.
    vscode.authentication.onDidChangeSessions((e) => {
      if (e.provider.id === (ado ? "microsoft" : "github")) {
        void updateState();
      }
    }),
    context.secrets.onDidChange(() => void updateState()),
    command("smartPrReview.signIn", async () => {
      // Existing credentials may be revoked: replace them instead of reusing them.
      await auth.signIn(await auth.isSignedIn());
      await updateState();
    }),
    command("smartPrReview.setAzureDevOpsPat", async () => {
      await ado?.promptPat();
      await updateState();
    }),
    command("smartPrReview.refresh", async () => {
      list.refresh();
      const before = session.current?.pr.headSha;
      if (before) {
        await session.refresh();
        if (session.current?.pr.headSha !== before) {
          void vscode.window.showInformationMessage(vscode.l10n.t("The pull request has new commits since you opened it."));
        }
      }
    }),
    command("smartPrReview.searchPullRequests", async () => {
      const id = await searchPullRequests(provider);
      if (id !== undefined) {
        await vscode.commands.executeCommand("smartPrReview.openPullRequest", id);
      }
    }),
    command("smartPrReview.exitReview", async () => {
      session.close();
      await closeReviewTabs();
    }),
    command("smartPrReview.openInBrowser", async (node?: { pr?: { url: string } }) => {
      const uri = browserUri(node?.pr?.url ?? session.require().pr.url);
      if (uri) {
        await vscode.env.openExternal(uri);
      }
    }),
    command("smartPrReview.openPullRequest", async (id: number) => {
      await vscode.window.withProgress({ location: { viewId: "smartPrReview.currentPullRequest" } }, () => session.open(id));
      await vscode.commands.executeCommand("smartPrReview.currentPullRequest.focus");
    }),
    command("smartPrReview.openFileDiff", (file: ChangedFile, line?: number) => openFileDiff(session, file, line)),
    command("smartPrReview.openThread", (thread: Thread) => openThread(session, thread)),
    command("smartPrReview.checkoutPullRequest", () =>
      vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: vscode.l10n.t("Checking out pull request…") }, () =>
        session.checkout(confirmReset),
      ),
    ),
    command("smartPrReview.openPublishPanel", () => PublishPanel.show(session)),
    command("smartPrReview.openReviewWindow", () => ReviewWindow.show(session, context.workspaceState)),
    command("smartPrReview.discardDrafts", async () => {
      const drafts = session.require().drafts.all();
      const discard = vscode.l10n.t("Discard");
      const message = vscode.l10n.t("Discard {0} draft comment(s)?", drafts.length);
      if ((await vscode.window.showWarningMessage(message, { modal: true }, discard)) === discard) {
        await session.removeDrafts(drafts.map((d) => d.id));
      }
    }),
    command("smartPrReview.addSuggestion", () => comments.addSuggestion(vscode.window.activeTextEditor)),
    command("smartPrReview.reviewWithAI", () => reviewWithAi(session, context.extensionPath, context.globalStorageUri.fsPath, context.globalState)),
    command("smartPrReview.createProfile", () => createProfile(root)),
    command("smartPrReview.createDraft", (reply: vscode.CommentReply) => comments.createDraft(reply)),
    command("smartPrReview.createSuggestion", (reply: vscode.CommentReply) => comments.createSuggestion(reply)),
    command("smartPrReview.replyThread", (reply: vscode.CommentReply) => comments.reply(reply)),
    command("smartPrReview.resolveThread", (thread: vscode.CommentThread) => comments.setResolved(thread, true)),
    command("smartPrReview.reopenThread", (thread: vscode.CommentThread) => comments.setResolved(thread, false)),
    command("smartPrReview.editDraft", (comment: DraftComment) => comments.editDraft(comment)),
    command("smartPrReview.saveDraft", (comment: DraftComment) => comments.saveDraft(comment)),
    command("smartPrReview.cancelEditDraft", () => comments.cancelEditDraft()),
    command("smartPrReview.deleteDraft", (comment: DraftComment) => comments.deleteDraft(comment)),
  );
  await updateState();
}

export function deactivate(): void {}

/** The first workspace folder whose remote is supported; else a git repository with an unsupported remote. */
async function findRepository(): Promise<{ root: string; remoteName: string; remote: RemoteInfo | undefined } | undefined> {
  const hosts = vscode.workspace.getConfiguration("smartPrReview").get<string[]>("azureDevOps.hosts", []);
  let unsupported: { root: string; remoteName: string; remote: undefined } | undefined;
  // ponytail: first workspace folder with a supported remote; multi-repo workspaces pick one
  for (const folder of vscode.workspace.workspaceFolders ?? []) {
    const root = await git.repositoryRoot(folder.uri.fsPath);
    const picked = root ? await git.pickRemote(root) : undefined;
    if (!root || !picked) {
      continue;
    }
    const remote = parseRemote(picked.url, hosts);
    if (remote) {
      return { root, remoteName: picked.name, remote };
    }
    unsupported ??= { root, remoteName: picked.name, remote: undefined };
  }
  return unsupported;
}

async function confirmReset(branch: string): Promise<boolean> {
  const reset = vscode.l10n.t("Reset Branch");
  const message = vscode.l10n.t(
    "Local branch {0} has commits that are not in the pull request (it was probably force-pushed). Reset {0} to the pull request head? Those commits will be lost.",
    branch,
  );
  return (await vscode.window.showWarningMessage(message, { modal: true }, reset)) === reset;
}

async function setState(state: State): Promise<void> {
  await vscode.commands.executeCommand("setContext", "smartPrReview.state", state);
}

/** Runs a user action and reports its failure. */
async function runTask(task: () => Promise<unknown>, auth: Auth): Promise<void> {
  try {
    await task();
  } catch (error) {
    await report(error, auth, () => runTask(task, auth));
  }
}

/** Error message with its recovery actions: "Sign In" (replacing the credentials), "Use a Personal Access Token" or "Retry". */
async function report(error: unknown, auth: Auth, retry: () => Promise<void>): Promise<void> {
  log.error(error instanceof Error ? error : String(error));
  const labels = {
    signIn: vscode.l10n.t("Sign In"),
    usePat: vscode.l10n.t("Use a Personal Access Token"),
    retry: vscode.l10n.t("Retry"),
  };
  const choice = await vscode.window.showErrorMessage(describeError(error), ...recoveryActions(error, auth).map((a) => labels[a]));
  if (choice === labels.signIn) {
    await auth.signIn(true);
  } else if (choice === labels.usePat && auth instanceof AzureDevOpsAuth) {
    await auth.promptPat();
  } else if (choice === labels.retry) {
    await retry();
  }
}
