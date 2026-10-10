import * as vscode from "vscode";
import type { ReviewSession } from "./session";
import { checkReviewWorktree, createReviewWorktree, removeReviewWorktree, reviewWorktrees } from "./worktrees";

export async function createWorktree(session: ReviewSession): Promise<void> {
  if (!vscode.workspace.isTrusted) { return; }
  const { pr } = session.require();
  const worktree = await vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: vscode.l10n.t("Creating review worktree…") },
    () => createReviewWorktree(session.repoRoot, pr.id, pr.headSha),
  );
  const open = vscode.l10n.t("Open in New Window");
  if (await vscode.window.showInformationMessage(vscode.l10n.t("Review worktree created at {0}. It uses detached HEAD and leaves your branch unchanged.", worktree.path), open) === open) {
    await vscode.commands.executeCommand("vscode.openFolder", vscode.Uri.file(worktree.path), true);
  }
}

export async function manageWorktrees(root: string, prId?: number): Promise<void> {
  if (!vscode.workspace.isTrusted) { return; }
  const worktrees = (await reviewWorktrees(root)).filter((w) => prId === undefined || w.prId === prId);
  if (!worktrees.length) {
    if (prId === undefined) { void vscode.window.showInformationMessage(vscode.l10n.t("No review worktrees in this repository.")); }
    return;
  }
  const choice = await vscode.window.showQuickPick(worktrees.map((w) => ({ label: `#${w.prId} · ${w.headSha.slice(0, 8)}`, description: w.path, worktree: w })), {
    title: vscode.l10n.t("Review Worktrees"), placeHolder: vscode.l10n.t("Choose a worktree to open or remove"),
  });
  if (!choice) { return; }
  const open = vscode.l10n.t("Open in New Window");
  const remove = vscode.l10n.t("Remove Worktree");
  const action = await vscode.window.showQuickPick([open, remove], { title: choice.worktree.path });
  if (action === open) {
    await checkReviewWorktree(root, choice.worktree);
    await vscode.commands.executeCommand("vscode.openFolder", vscode.Uri.file(choice.worktree.path), true);
  } else if (action === remove && await vscode.window.showWarningMessage(
    vscode.l10n.t("Remove {0}? Save your work and close any editor windows using this folder first. Worktrees with local files or new commits will not be removed.", choice.worktree.path),
    { modal: true }, remove,
  ) === remove) {
    await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: remove }, () => removeReviewWorktree(root, choice.worktree));
    void vscode.window.showInformationMessage(vscode.l10n.t("Review worktree removed."));
  }
}
