import { existsSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import { isAbsolute, join, relative, resolve } from "node:path";
import * as vscode from "vscode";
import type { ChangedFile } from "../git";
import { aiLog } from "../log";
import type { ReviewSession } from "../review/session";
import {
  apiBase,
  type ChatModel,
  type CliEngineConfig,
  type CliModel,
  copilotModels,
  DEFAULT_CONTEXT_WINDOW,
  DEFAULT_MAX_OUTPUT_TOKENS,
  findExecutable,
  isModelName,
  listCliModels,
  listUrlModels,
  type UrlEngineConfig,
  urlChatModel,
} from "./engines";
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
  // Any case, and any name the filesystem folds (ſ for s on APFS): .VSCODE/settings.json is the workspace settings file too.
  const isSettings = (path: string | undefined) => path?.toUpperCase().toLowerCase() === ".vscode/settings.json";
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
let running: { label: string; cancel: vscode.CancellationTokenSource; done: Promise<void> } | undefined;
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

/**
 * Cancels the AI review running and waits until its engines have exited. For when VS Code closes or the
 * extension is disabled: an engine left behind would go on spending on a review nobody reads.
 */
export async function stopAiReview(): Promise<void> {
  const review = running;
  review?.cancel.cancel();
  await review?.done;
}

/** "Review with AI": pick profiles, pick an engine, review, then report. */
export async function reviewWithAi(
  session: ReviewSession,
  extensionPath: string,
  storagePath: string,
  state: vscode.Memento,
  secrets: vscode.SecretStorage,
): Promise<void> {
  if (busy) {
    void vscode.window.showInformationMessage(vscode.l10n.t("An AI review is already running. Wait for it to finish, or cancel it."));
    return;
  }
  busy = true;
  try {
    await review(session, extensionPath, storagePath, state, secrets);
  } finally {
    busy = false;
  }
}

async function review(
  session: ReviewSession,
  extensionPath: string,
  storagePath: string,
  state: vscode.Memento,
  secrets: vscode.SecretStorage,
): Promise<void> {
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
  const engine = await pickEngine(config, state, secrets);
  if (!engine) {
    return;
  }
  // Cancellable from the notification and from the review window, which has no notifications.
  const cancel = new vscode.CancellationTokenSource();
  let settle = () => {};
  running = { label: engineLabel(engine), cancel, done: new Promise((resolve) => (settle = resolve)) };
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
    settle();
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

/** An engine of smartPrReview.engines: a command, or a server reached by URL. */
export type EngineConfig = CliEngineConfig | UrlEngineConfig;

/** Bundled engines plus the user's own: an object setting would otherwise replace its default entirely. */
export function aiEngines(inspected: { defaultValue?: unknown; globalValue?: unknown } | undefined): Record<string, EngineConfig> {
  return { ...asEngines(inspected?.defaultValue), ...asEngines(inspected?.globalValue) };
}

function isHttpUrl(value: string): boolean {
  try {
    return ["http:", "https:"].includes(new URL(value).protocol);
  } catch {
    return false;
  }
}

const positiveInteger = (value: unknown): number | undefined => (Number.isInteger(value) && (value as number) > 0 ? (value as number) : undefined);

function asEngines(value: unknown): Record<string, EngineConfig> {
  if (typeof value !== "object" || value === null) {
    return {};
  }
  const engines: Record<string, EngineConfig> = {};
  for (const [id, entry] of Object.entries(value)) {
    if (typeof entry !== "object" || entry === null) {
      continue;
    }
    const e = entry as {
      command?: unknown;
      args?: unknown;
      modelArg?: unknown;
      models?: unknown;
      listModelsArgs?: unknown;
      defaultModel?: unknown;
      url?: unknown;
      contextWindow?: unknown;
      maxOutputTokens?: unknown;
    };
    const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every((a) => typeof a === "string");
    // The model fields are optional extras: a malformed one is left out, the engine still works.
    const models = {
      ...(strings(e.models) ? { models: e.models.filter(isModelName) } : {}),
      ...(typeof e.defaultModel === "string" && isModelName(e.defaultModel) ? { defaultModel: e.defaultModel } : {}),
    };
    if (typeof e.url === "string" && isHttpUrl(e.url)) {
      engines[id] = {
        url: e.url,
        contextWindow: positiveInteger(e.contextWindow) ?? DEFAULT_CONTEXT_WINDOW,
        maxOutputTokens: positiveInteger(e.maxOutputTokens) ?? DEFAULT_MAX_OUTPUT_TOKENS,
        ...models,
      };
    } else if (typeof e.command === "string" && e.command && (e.args === undefined || strings(e.args))) {
      engines[id] = {
        command: e.command,
        args: e.args as string[] | undefined,
        ...(typeof e.modelArg === "string" && e.modelArg ? { modelArg: e.modelArg } : {}),
        ...(strings(e.listModelsArgs) && e.listModelsArgs.length ? { listModelsArgs: e.listModelsArgs } : {}),
        ...models,
      };
    }
  }
  return engines;
}

/** The model of an engine picked last ("" for a CLI's own default), offered first next time. */
const lastModelKey = (engineId: string) => `smartPrReview.cliModel.${engineId}`;

/**
 * What the model picker of a CLI engine offers, in order: the model picked last (the engine's default one
 * until then), the CLI's own default (undefined), then the engine's models and the ones the CLI listed.
 * `last` is "" when the CLI's own default was picked last.
 */
export function cliModelChoices(
  engine: Pick<CliEngineConfig, "models" | "defaultModel">,
  listed: readonly CliModel[],
  last: string | undefined,
): (CliModel | undefined)[] {
  const known = new Map<string, CliModel>();
  for (const model of [...(engine.models ?? []).map((id): CliModel => ({ id })), ...listed]) {
    const seen = known.get(model.id);
    if (isModelName(model.id) && (!seen || (!seen.name && model.name))) {
      known.set(model.id, model);
    }
  }
  const first = last ?? engine.defaultModel;
  const preferred = first && isModelName(first) ? (known.get(first) ?? { id: first }) : undefined;
  return [...(preferred ? [preferred] : []), undefined, ...[...known.values()].filter((m) => m.id !== preferred?.id)];
}

/** Seconds an engine may take to list its models before the picker goes on without them. */
const LIST_MODELS_TIMEOUT_MS = 20_000;

interface ModelItem extends vscode.QuickPickItem {
  /** A model, the CLI's own default (undefined) or "type another one". */
  choice: { model: string | undefined } | "other";
}

interface ModelSource {
  /** Lists the engine's models, added to the picker when they arrive. */
  list?: (token: vscode.CancellationToken) => Promise<CliModel[]>;
  /** What the "Default model" entry says it is. Without it there is no such entry: a URL engine needs a model. */
  ownDefault?: string;
  /** Prompt of the box where another model is typed. */
  otherPrompt: string;
}

/**
 * Asks for the model of an engine. Returns the model, undefined in `model` for the CLI's own default, or
 * undefined when the picker was dismissed. The picker opens at once and the listed models are added when
 * they arrive.
 */
async function pickModel(
  id: string,
  engine: Pick<CliEngineConfig, "models" | "defaultModel">,
  source: ModelSource,
  state: vscode.Memento,
): Promise<{ model: string | undefined } | undefined> {
  const key = lastModelKey(id);
  const last = state.get<string>(key);
  const items = (listed: readonly CliModel[]): ModelItem[] => [
    ...cliModelChoices(engine, listed, last).flatMap((model): ModelItem[] => {
      if (model) {
        return [{ label: model.id, description: model.name, choice: { model: model.id } }];
      }
      return source.ownDefault ? [{ label: vscode.l10n.t("Default model"), description: source.ownDefault, choice: { model: undefined } }] : [];
    }),
    { label: vscode.l10n.t("Other…"), description: vscode.l10n.t("type a model name"), choice: "other" },
  ];
  const pick = vscode.window.createQuickPick<ModelItem>();
  const listing = new vscode.CancellationTokenSource();
  let choice: ModelItem["choice"] | undefined;
  try {
    pick.title = vscode.l10n.t("Review with AI");
    pick.placeholder = vscode.l10n.t("Choose the model of {0}", id);
    pick.matchOnDescription = true;
    pick.items = items([]);
    if (source.list) {
      pick.busy = true;
      void source
        .list(listing.token)
        .then(
          (listed) => {
            if (listing.token.isCancellationRequested) {
              return;
            }
            // The same entry stays under the cursor when the list grows.
            const active = pick.activeItems[0]?.label;
            pick.keepScrollPosition = true;
            pick.items = items(listed);
            pick.activeItems = pick.items.filter((item) => item.label === active).slice(0, 1);
          },
          (error) => {
            // A listing stopped because the picker closed is not worth a warning.
            if (!listing.token.isCancellationRequested && !(error instanceof vscode.CancellationError)) {
              aiLog.warn(`Could not list the models of ${id}: ${error instanceof Error ? error.message : String(error)}`);
            }
          },
        )
        .then(() => {
          if (!listing.token.isCancellationRequested) {
            pick.busy = false;
          }
        });
    }
    choice = await new Promise<ModelItem["choice"] | undefined>((resolve) => {
      pick.onDidAccept(() => resolve(pick.selectedItems[0]?.choice));
      pick.onDidHide(() => resolve(undefined));
      pick.show();
    });
  } finally {
    listing.cancel();
    listing.dispose();
    pick.dispose();
  }
  if (choice === undefined) {
    return undefined;
  }
  if (choice === "other") {
    const typed = await vscode.window.showInputBox({
      title: vscode.l10n.t("Review with AI"),
      prompt: source.otherPrompt,
      validateInput: (value) => (isModelName(value.trim()) ? undefined : vscode.l10n.t("Enter a model name without spaces.")),
    });
    if (!typed?.trim()) {
      return undefined;
    }
    choice = { model: typed.trim() };
  }
  await state.update(key, choice.model ?? "");
  return choice;
}

async function pickEngine(config: vscode.WorkspaceConfiguration, state: vscode.Memento, secrets: vscode.SecretStorage): Promise<Engine | undefined> {
  const engines = aiEngines(config.inspect("engines"));
  const defaultId = config.get<string>("defaultEngine", "copilot");
  const candidates = [
    {
      id: "copilot",
      label: "GitHub Copilot",
      description: vscode.l10n.t("VS Code language models"),
      available: copilotModels().then((models) => models.length > 0),
    },
    // A server is not probed here: one on the network could keep the picker waiting.
    ...Object.entries(engines).map(([id, e]) =>
      "url" in e
        ? { id, label: id, description: e.url, available: Promise.resolve(true) }
        : { id, label: id, description: e.command, available: findExecutable(e.command).then(Boolean) },
    ),
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
  if ("url" in engine) {
    return pickUrlEngine(choice.id, engine, state, secrets);
  }
  const executable = await findExecutable(engine.command);
  if (!executable) {
    return reportMissing(
      vscode.l10n.t("{0} was not found in PATH. Install it or change the setting smartPrReview.engines.", engine.command),
      choice.id,
    );
  }
  const args = engine.args ?? [];
  if (!engine.modelArg) {
    return { kind: "cli", id: choice.id, executable, args };
  }
  const listModelsArgs = engine.listModelsArgs;
  const picked = await pickModel(
    choice.id,
    engine,
    {
      list: listModelsArgs && ((token) => listCliModels(executable, listModelsArgs, token, LIST_MODELS_TIMEOUT_MS)),
      ownDefault: vscode.l10n.t("the one {0} is set up to use", engine.command),
      otherPrompt: vscode.l10n.t("Model name, as {0} expects it after {1}", engine.command, engine.modelArg),
    },
    state,
  );
  if (!picked) {
    return undefined;
  }
  return picked.model === undefined
    ? { kind: "cli", id: choice.id, executable, args }
    : { kind: "cli", id: choice.id, executable, args: [...args, engine.modelArg, picked.model], model: picked.model };
}

/** The secret that holds the API key of a URL engine: per URL, so a key never goes to a server it was not set for. */
export const apiKeySecret = (engine: Pick<UrlEngineConfig, "url">) => `smartPrReview.engineApiKey:${apiBase(engine)}`;

async function pickUrlEngine(id: string, engine: UrlEngineConfig, state: vscode.Memento, secrets: vscode.SecretStorage): Promise<Engine | undefined> {
  if (engine.maxOutputTokens >= engine.contextWindow) {
    void vscode.window.showErrorMessage(
      vscode.l10n.t(
        "maxOutputTokens of {0} ({1}) leaves no room for the prompt in its contextWindow ({2}). Lower it in the setting smartPrReview.engines.",
        id,
        engine.maxOutputTokens,
        engine.contextWindow,
      ),
    );
    return undefined;
  }
  const apiKey = (await secrets.get(apiKeySecret(engine))) || undefined;
  const picked = await pickModel(
    id,
    engine,
    {
      list: (token) => listUrlModels(engine, apiKey, token, LIST_MODELS_TIMEOUT_MS),
      otherPrompt: vscode.l10n.t("Model name, as {0} lists it", engine.url),
    },
    state,
  );
  return picked?.model === undefined ? undefined : { kind: "url", id, model: urlChatModel(engine, picked.model, apiKey) };
}

/** "Set API Key of an AI Engine": kept in SecretStorage, per URL. An empty answer removes the stored key. */
export async function setEngineApiKey(secrets: vscode.SecretStorage): Promise<void> {
  const engines = Object.entries(aiEngines(vscode.workspace.getConfiguration("smartPrReview").inspect("engines"))).flatMap(([id, e]) =>
    "url" in e ? [{ label: id, description: e.url, engine: e }] : [],
  );
  if (engines.length === 0) {
    void vscode.window.showInformationMessage(vscode.l10n.t("No AI engine has a url. Add one to the setting smartPrReview.engines first."));
    return;
  }
  const choice =
    engines.length === 1
      ? engines[0]
      : await vscode.window.showQuickPick(engines, { title: vscode.l10n.t("API key of an AI engine"), placeHolder: vscode.l10n.t("Choose the engine") });
  if (!choice) {
    return;
  }
  const value = await vscode.window.showInputBox({
    title: vscode.l10n.t("API key of {0}", choice.label),
    prompt: vscode.l10n.t("The key {0} expects. Leave it empty to remove the stored key.", choice.engine.url),
    password: true,
    ignoreFocusOut: true,
  });
  if (value === undefined) {
    return;
  }
  if (value.trim()) {
    await secrets.store(apiKeySecret(choice.engine), value.trim());
  } else {
    await secrets.delete(apiKeySecret(choice.engine));
  }
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
exclude:
  - "**/package-lock.json"
  - "**/*.lock"
  - "**/*.min.js"
  - "**/__snapshots__/**"
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
