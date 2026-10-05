import { existsSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import { isAbsolute, join, relative, resolve } from "node:path";
import * as vscode from "vscode";
import type { ChangedFile } from "../git";
import { aiLog } from "../log";
import type { ReviewSession } from "../review/session";
import { type ChatModel, type CliEngineConfig, copilotModels, findExecutable } from "./engines";
import { appliesTo, loadProfiles, type Profile } from "./profiles";
import { type Engine, engineLabel, runAiReview } from "./runReview";

export const DEFAULT_PROFILES_FOLDER = ".smart-review/profiles";

interface Inspected<T> {
  defaultValue?: T;
  globalValue?: T;
  workspaceValue?: T;
  workspaceFolderValue?: T;
}

/**
 * A setting the repository may set. A pull request that edits the workspace settings could use it to drop
 * the team's profiles or steer the AI, so then only the user's own value or the default counts.
 */
export function repositorySetting<T>(inspected: Inspected<T> | undefined, changed: readonly ChangedFile[]): T | undefined {
  // Any case: on macOS and Windows, .VSCODE/settings.json is the workspace settings file too.
  const isSettings = (path: string | undefined) => path?.toLowerCase() === ".vscode/settings.json";
  const editsSettings = changed.some((f) => isSettings(f.path) || isSettings(f.oldPath));
  const workspace = editsSettings ? undefined : (inspected?.workspaceFolderValue ?? inspected?.workspaceValue);
  return workspace ?? inspected?.globalValue ?? inspected?.defaultValue;
}

export function profilesFolder(inspected: Inspected<string> | undefined, changed: readonly ChangedFile[]): string {
  return repositorySetting(inspected, changed) ?? DEFAULT_PROFILES_FOLDER;
}

/**
 * The language the AI writes in: the setting when it is a plain language name (it goes into the prompt),
 * else the pull request's own, falling back to VS Code's display language.
 */
export function aiLanguage(setting: unknown, displayLanguage: string): string {
  const configured = typeof setting === "string" ? setting.trim() : "";
  if (/^[\p{L} ()-]{1,40}$/u.test(configured)) {
    return configured;
  }
  let fallback = displayLanguage;
  try {
    fallback = new Intl.DisplayNames(["en"], { type: "language" }).of(displayLanguage) ?? displayLanguage;
  } catch {
    // Not a language code Intl knows: the model reads the code.
  }
  return `the language of the pull request title and description (${fallback} if that is unclear)`;
}

/** The Copilot model picked last, offered first next time. */
const LAST_MODEL_KEY = "smartPrReview.copilotModel";

/** The models with the one used last (by id) first. */
export function orderModels<T extends { id: string }>(models: readonly T[], lastId: string | undefined): T[] {
  return [...models].sort((a, b) => Number(b.id === lastId) - Number(a.id === lastId));
}

/** ai.timeoutMinutes in milliseconds, between 1 minute and 1 day: Node turns timers above 2^31-1 ms into 1 ms. */
export function aiTimeoutMs(minutes: unknown): number {
  const value = typeof minutes === "number" && Number.isFinite(minutes) ? minutes : 10;
  return Math.min(Math.max(value, 1), 1440) * 60_000;
}

const INSTALL_GUIDES = new Map([
  ["copilot", "https://marketplace.visualstudio.com/items?itemName=GitHub.copilot-chat"],
  ["claude", "https://code.claude.com/docs/en/setup"],
  ["codex", "https://github.com/openai/codex"],
  ["cursor", "https://cursor.com/docs/cli/installation"],
]);

/** Where to install a bundled engine; undefined for the user's own engines. */
export function installGuide(id: string): string | undefined {
  return INSTALL_GUIDES.get(id);
}

/** Says an engine is missing, with a link to its installation guide when there is one. */
async function reportMissing(message: string, id: string): Promise<undefined> {
  const guide = installGuide(id);
  const install = vscode.l10n.t("How to Install");
  const choice = await vscode.window.showErrorMessage(message, ...(guide ? [install] : []));
  if (guide && choice === install) {
    await vscode.env.openExternal(vscode.Uri.parse(guide));
  }
  return undefined;
}

/** "A, B: quota; C: bad JSON": profiles that failed the same way are listed once. */
export function describeFailures(failures: readonly { profile: string; error: string }[]): string {
  const byError = new Map<string, string[]>();
  for (const f of failures) {
    byError.set(f.error, [...(byError.get(f.error) ?? []), f.profile]);
  }
  return [...byError].map(([error, profiles]) => `${profiles.join(", ")}: ${error}`).join("; ");
}

/** The AI review running: one at a time, so a second click cannot start another one unnoticed. */
let busy = false;
let running: { label: string; cancel: vscode.CancellationTokenSource } | undefined;
const aiReviewChanged = new vscode.EventEmitter<void>();
/** Fires when an AI review starts or ends. */
export const onDidChangeAiReview = aiReviewChanged.event;

/** The engine of the AI review running, as progress names it; undefined when none runs. */
export function runningAiReview(): string | undefined {
  return running?.label;
}

export function cancelAiReview(): void {
  running?.cancel.cancel();
}

/** "Review with AI": pick profiles, pick an engine, review, then report. */
export async function reviewWithAi(session: ReviewSession, extensionPath: string, storagePath: string, state: vscode.Memento): Promise<void> {
  if (busy) {
    void vscode.window.showInformationMessage(vscode.l10n.t("An AI review is already running. Wait for it to finish, or cancel it."));
    return;
  }
  busy = true;
  try {
    await review(session, extensionPath, storagePath, state);
  } finally {
    busy = false;
  }
}

async function review(session: ReviewSession, extensionPath: string, storagePath: string, state: vscode.Memento): Promise<void> {
  const c = session.require();
  const config = vscode.workspace.getConfiguration("smartPrReview", vscode.Uri.file(session.repoRoot));
  const profiles = await loadProfiles({
    repoRoot: session.repoRoot,
    folder: profilesFolder(config.inspect<string>("profilesPath"), c.files),
    baseSha: c.mergeBase,
    changed: c.files,
    bundledDir: join(extensionPath, "profiles"),
  });
  const selected = await pickProfiles(
    profiles,
    c.files.filter((f) => f.status !== "D").map((f) => f.path),
  );
  if (!selected?.length) {
    return;
  }
  const engine = await pickEngine(config, state);
  if (!engine) {
    return;
  }
  // Cancellable from the notification and from the review window, which has no notifications.
  const cancel = new vscode.CancellationTokenSource();
  running = { label: engineLabel(engine), cancel };
  aiReviewChanged.fire();
  let result;
  try {
    result = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: vscode.l10n.t("Reviewing with AI ({0})…", engineLabel(engine)), cancellable: true },
      (_progress, token) => {
        token.onCancellationRequested(() => cancel.cancel());
        return runAiReview({
          session,
          profiles: selected,
          engine,
          worktrees: join(storagePath, "worktrees"),
          timeoutMs: aiTimeoutMs(config.get("ai.timeoutMinutes")),
          labelComments: config.get<boolean>("ai.labelComments", false),
          language: aiLanguage(repositorySetting(config.inspect<string>("ai.language"), c.files), vscode.env.language),
          token: cancel.token,
          log: aiLog,
        });
      },
    );
  } catch (error) {
    if (error instanceof vscode.CancellationError) {
      return;
    }
    throw error;
  } finally {
    running = undefined;
    cancel.dispose();
    aiReviewChanged.fire();
  }
  if (result.failures.length) {
    const show = vscode.l10n.t("Show Output");
    const message = vscode.l10n.t(
      "AI review added {0} draft(s); {1} profile(s) failed: {2}",
      result.drafts,
      result.failures.length,
      describeFailures(result.failures),
    );
    if ((await vscode.window.showWarningMessage(message, show)) === show) {
      aiLog.show();
    }
  } else if (result.reviewed.length === 0) {
    void vscode.window.showInformationMessage(vscode.l10n.t("No changed file matches the chosen profiles."));
  } else {
    void vscode.window.showInformationMessage(
      vscode.l10n.t("AI review added {0} draft(s) from {1} profile(s). Review them before publishing.", result.drafts, result.reviewed.length),
    );
  }
}

async function pickProfiles(profiles: readonly Profile[], changed: readonly string[]): Promise<Profile[] | undefined> {
  if (profiles.length === 0) {
    void vscode.window.showWarningMessage(vscode.l10n.t("No review profiles found."));
    return undefined;
  }
  const items = profiles.map((profile) => {
    const applies = changed.some((path) => appliesTo(profile, path));
    const origin = profile.source === "bundled" ? vscode.l10n.t("bundled") : profile.local ? vscode.l10n.t("local") : "";
    return {
      label: profile.name,
      description: [profile.description, origin && `(${origin})`].filter(Boolean).join(" "),
      detail: applies ? undefined : vscode.l10n.t("No changed file matches this profile."),
      picked: applies,
      profile,
    };
  });
  const picked = await vscode.window.showQuickPick(items, {
    canPickMany: true,
    title: vscode.l10n.t("Review with AI"),
    placeHolder: vscode.l10n.t("Choose the review profiles"),
  });
  return picked?.map((item) => item.profile);
}

/** Bundled engines plus the user's own: an object setting would otherwise replace its default entirely. */
export function cliEngines(inspected: { defaultValue?: unknown; globalValue?: unknown } | undefined): Record<string, CliEngineConfig> {
  return { ...asEngines(inspected?.defaultValue), ...asEngines(inspected?.globalValue) };
}

function asEngines(value: unknown): Record<string, CliEngineConfig> {
  if (typeof value !== "object" || value === null) {
    return {};
  }
  const engines: Record<string, CliEngineConfig> = {};
  for (const [id, entry] of Object.entries(value)) {
    const e = entry as { command?: unknown; args?: unknown };
    if (
      typeof e?.command === "string" &&
      e.command &&
      (e.args === undefined || (Array.isArray(e.args) && e.args.every((a) => typeof a === "string")))
    ) {
      engines[id] = { command: e.command, args: e.args as string[] | undefined };
    }
  }
  return engines;
}

async function pickEngine(config: vscode.WorkspaceConfiguration, state: vscode.Memento): Promise<Engine | undefined> {
  const engines = cliEngines(config.inspect("engines"));
  const defaultId = config.get<string>("defaultEngine", "copilot");
  const candidates = [
    {
      id: "copilot",
      label: "GitHub Copilot",
      description: vscode.l10n.t("VS Code language models"),
      available: copilotModels().then((models) => models.length > 0),
    },
    ...Object.entries(engines).map(([id, e]) => ({ id, label: id, description: e.command, available: findExecutable(e.command).then(Boolean) })),
  ];
  const items = await Promise.all(
    candidates.map(async (c) => ({
      id: c.id,
      label: c.id === defaultId ? `$(star-full) ${c.label}` : c.label,
      description: (await c.available) ? c.description : `${c.description} · ${vscode.l10n.t("not available")}`,
    })),
  );
  items.sort((a, b) => Number(b.id === defaultId) - Number(a.id === defaultId));
  const choice = await vscode.window.showQuickPick(items, {
    title: vscode.l10n.t("Review with AI"),
    placeHolder: vscode.l10n.t("Choose the engine"),
  });
  if (!choice) {
    return undefined;
  }
  if (choice.id === "copilot") {
    const model = await pickCopilotModel(state);
    return model && { kind: "copilot", id: "copilot", model };
  }
  const engine = engines[choice.id];
  const executable = await findExecutable(engine.command);
  if (!executable) {
    return reportMissing(
      vscode.l10n.t("{0} was not found in PATH. Install it or change the setting smartPrReview.engines.", engine.command),
      choice.id,
    );
  }
  return { kind: "cli", id: choice.id, executable, args: engine.args ?? [] };
}

async function pickCopilotModel(state: vscode.Memento): Promise<ChatModel | undefined> {
  const models = orderModels(await copilotModels(), state.get<string>(LAST_MODEL_KEY));
  if (models.length === 0) {
    return reportMissing(vscode.l10n.t("GitHub Copilot is not available. Install GitHub Copilot and sign in, then try again."), "copilot");
  }
  const model =
    models.length === 1
      ? models[0]
      : (
          await vscode.window.showQuickPick(
            models.map((m) => ({ label: m.name, model: m })),
            { title: vscode.l10n.t("Review with AI"), placeHolder: vscode.l10n.t("Choose the GitHub Copilot model") },
          )
        )?.model;
  if (model) {
    await state.update(LAST_MODEL_KEY, model.id);
  }
  return model;
}

export function profileTemplate(name: string): string {
  return `---
name: ${JSON.stringify(name)}
description: What this profile looks for
include:
  - "**/*"
---
Review the changes for <what this profile cares about>. For each problem, say what can go wrong and how to fix it.

- <First thing to check>
- <Second thing to check>
`;
}

/** "Create Review Profile": a template in the repository's profiles folder, opened for editing. */
export async function createProfile(repoRoot: string): Promise<void> {
  const config = vscode.workspace.getConfiguration("smartPrReview", vscode.Uri.file(repoRoot));
  const folder = resolve(repoRoot, config.get<string>("profilesPath", DEFAULT_PROFILES_FOLDER));
  const inside = relative(repoRoot, folder);
  if (inside.startsWith("..") || isAbsolute(inside)) {
    throw new Error(vscode.l10n.t("The profiles folder must be inside the repository."));
  }
  const name = await vscode.window.showInputBox({
    title: vscode.l10n.t("New review profile"),
    prompt: vscode.l10n.t('Profile name, for example "Security"'),
    validateInput: (value) => (value.trim() ? undefined : vscode.l10n.t("Enter a name.")),
  });
  if (!name?.trim()) {
    return;
  }
  const slug =
    name
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "") || "profile";
  const file = join(folder, `${slug}.md`);
  if (!existsSync(file)) {
    await mkdir(folder, { recursive: true });
    await writeFile(file, profileTemplate(name.trim()));
  }
  await vscode.window.showTextDocument(vscode.Uri.file(file));
}
