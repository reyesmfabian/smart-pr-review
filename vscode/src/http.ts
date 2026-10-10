import * as vscode from "vscode";
import { log } from "./log";

export class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly headers: Headers = new Headers(),
  ) {
    super(message);
  }
}

/** fetch + JSON. Throws HttpError on non-2xx. Never logs headers (they carry tokens). */
export async function requestJson<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, init);
  log.debug(`${init.method ?? "GET"} ${url} → ${response.status}`);
  // Azure DevOps answers rejected credentials with 203 and an HTML sign-in page; a proxy or SSO in front
  // of a server may answer 200 with one. The APIs we call only return JSON on success.
  const html = response.ok && (response.headers.get("content-type") ?? "").includes("text/html");
  if (response.status === 203 || html) {
    throw new HttpError(401, "Not authorized", response.headers);
  }
  const text = await response.text();
  if (!response.ok) {
    throw new HttpError(response.status, errorMessage(text) || response.statusText, response.headers);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

function errorMessage(text: string): string {
  try {
    const body = JSON.parse(text) as { message?: string; errors?: unknown[] };
    const details = (body.errors ?? []).map((e) => (typeof e === "string" ? e : (e as { message?: string }).message));
    return [body.message, ...details].filter(Boolean).join(" — ");
  } catch {
    return text.slice(0, 200);
  }
}

export function describeError(error: unknown): string {
  if (!(error instanceof HttpError)) {
    return error instanceof Error ? error.message : String(error);
  }
  const reset = error.headers.get("x-ratelimit-reset");
  if ((error.status === 403 || error.status === 429) && error.headers.get("x-ratelimit-remaining") === "0" && reset) {
    return vscode.l10n.t("GitHub rate limit reached. Try again after {0}.", new Date(Number(reset) * 1000).toLocaleTimeString());
  }
  switch (error.status) {
    case 401:
      return vscode.l10n.t("Your session expired or you are not signed in. Sign in and try again.");
    case 403:
      return vscode.l10n.t("Permission denied: {0}. Check that your account or token can access this repository.", error.message);
    case 404:
      return vscode.l10n.t("Not found: {0}. The pull request or repository may not exist, or you may not have access.", error.message);
    case 422:
      return vscode.l10n.t("The server rejected the request: {0}", error.message);
    default:
      return vscode.l10n.t("Request failed ({0}): {1}", error.status, error.message);
  }
}
