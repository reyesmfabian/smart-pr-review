import * as vscode from "vscode";
import { HttpError } from "./http";

/** Sign-in state and flow of the repository's provider. */
export interface Auth {
  isSignedIn(): Promise<boolean>;
  /** force: replace the current credentials, e.g. after a 401. */
  signIn(force: boolean): Promise<void>;
}

const GITHUB_SCOPES = ["repo"];

export const githubAuth = {
  async isSignedIn(): Promise<boolean> {
    return (await vscode.authentication.getSession("github", GITHUB_SCOPES, { silent: true })) !== undefined;
  },

  async signIn(force: boolean): Promise<void> {
    await vscode.authentication.getSession("github", GITHUB_SCOPES, force ? { forceNewSession: true } : { createIfNone: true });
  },

  async token(): Promise<string> {
    const session = await vscode.authentication.getSession("github", GITHUB_SCOPES, { silent: true });
    if (!session) {
      throw new HttpError(401, "Not signed in to GitHub");
    }
    return session.accessToken;
  },
} satisfies Auth & { token(): Promise<string> };

/** Azure DevOps resource: Microsoft Entra tokens for this scope are accepted by dev.azure.com. */
const AZURE_DEVOPS_SCOPES = ["499b84ac-1321-427f-aa17-267ca6975798/.default"];

export type MicrosoftToken = (mode: "silent" | "prompt" | "force") => Promise<string | undefined>;

async function microsoftToken(mode: "silent" | "prompt" | "force"): Promise<string | undefined> {
  const options = mode === "force" ? { forceNewSession: true } : mode === "prompt" ? { createIfNone: true } : { silent: true };
  return (await vscode.authentication.getSession("microsoft", AZURE_DEVOPS_SCOPES, options))?.accessToken;
}

/**
 * A personal access token stored per host in SecretStorage, or, on Azure DevOps Services only, the
 * VS Code Microsoft session. Microsoft tokens never go to Azure DevOps Server hosts.
 */
export class AzureDevOpsAuth implements Auth {
  constructor(
    private readonly host: string,
    private readonly secrets: vscode.SecretStorage,
    private readonly microsoft: MicrosoftToken = microsoftToken,
    /** Runs before the personal access token is sent, and throws to keep it (see httpConsent). */
    private readonly beforePat?: () => Promise<void>,
  ) {}

  get cloud(): boolean {
    return this.host === "dev.azure.com";
  }

  async isSignedIn(): Promise<boolean> {
    return (await this.pat()) !== undefined || (this.cloud && (await this.microsoft("silent")) !== undefined);
  }

  async signIn(force: boolean): Promise<void> {
    if (!this.cloud || (await this.pat()) !== undefined) {
      await this.promptPat();
    } else {
      await this.microsoft(force ? "force" : "prompt");
    }
  }

  /** Authorization header value for REST calls. */
  async authorization(): Promise<string> {
    const pat = await this.pat();
    if (pat) {
      await this.beforePat?.();
      return `Basic ${Buffer.from(`:${pat}`).toString("base64")}`;
    }
    const token = this.cloud ? await this.microsoft("silent") : undefined;
    if (token) {
      return `Bearer ${token}`;
    }
    throw new HttpError(401, "Not signed in to Azure DevOps");
  }

  /** Asks for a personal access token. An empty answer removes the stored one. */
  async promptPat(): Promise<void> {
    const value = await vscode.window.showInputBox({
      title: vscode.l10n.t("Azure DevOps Personal Access Token"),
      prompt: vscode.l10n.t("Token for {0} with the Code (Read & Write) scope. Leave it empty to remove the stored token.", this.host),
      password: true,
      ignoreFocusOut: true,
    });
    if (value === undefined) {
      return;
    }
    if (value.trim()) {
      await this.secrets.store(this.key, value.trim());
    } else {
      await this.secrets.delete(this.key);
    }
  }

  private get key(): string {
    return `smartPrReview.azureDevOps.pat:${this.host}`;
  }

  private async pat(): Promise<string | undefined> {
    return (await this.secrets.get(this.key)) || undefined;
  }
}

/**
 * Asks before a personal access token goes to an Azure DevOps Server over plain HTTP, where anyone on the
 * network can read it. A yes is remembered per server; after a no, the next request asks again.
 */
export function httpConsent(origin: string, state: vscode.Memento, ask = askHttp): () => Promise<void> {
  const key = `smartPrReview.httpAllowed:${origin}`;
  let pending: Promise<boolean> | undefined;
  return async () => {
    if (state.get<boolean>(key)) {
      return;
    }
    // Requests made at the same time (the views load in parallel) share one question.
    pending ??= ask(origin).finally(() => (pending = undefined));
    if (!(await pending)) {
      throw new Error(
        vscode.l10n.t(
          "The personal access token was not sent: {0} uses HTTP, which does not encrypt it. Use an HTTPS remote, or accept sending it over HTTP.",
          origin,
        ),
      );
    }
    await state.update(key, true);
  };
}

async function askHttp(origin: string): Promise<boolean> {
  const send = vscode.l10n.t("Send over HTTP");
  const message = vscode.l10n.t(
    "{0} uses HTTP: your personal access token would travel unencrypted, and anyone on the network could read it and act as you. Send it anyway?",
    origin,
  );
  return (await vscode.window.showWarningMessage(message, { modal: true }, send)) === send;
}

export type RecoveryAction = "signIn" | "usePat" | "retry";

/**
 * What a failed action offers: a 401 offers to sign in again and, on Azure DevOps Services, a personal
 * access token too (a Microsoft account outside the organization would otherwise loop on "Sign In").
 */
export function recoveryActions(error: unknown, auth: Auth): RecoveryAction[] {
  if (!(error instanceof HttpError && error.status === 401)) {
    return ["retry"];
  }
  return auth instanceof AzureDevOpsAuth && auth.cloud ? ["signIn", "usePat"] : ["signIn"];
}
