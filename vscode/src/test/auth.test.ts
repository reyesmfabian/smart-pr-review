import * as assert from "node:assert/strict";
import * as vscode from "vscode";
import { AzureDevOpsAuth, githubAuth, httpConsent, type MicrosoftToken, recoveryActions } from "../auth";
import { HttpError } from "../http";
import { memento } from "./fakes";

function secrets(initial: Record<string, string> = {}): vscode.SecretStorage {
  const data = new Map(Object.entries(initial));
  const changed = new vscode.EventEmitter<vscode.SecretStorageChangeEvent>();
  return {
    get: async (key: string) => data.get(key),
    store: async (key: string, value: string) => {
      data.set(key, value);
    },
    delete: async (key: string) => {
      data.delete(key);
    },
    onDidChange: changed.event,
  } as unknown as vscode.SecretStorage;
}

/** Microsoft session stub that records how it was asked. */
function microsoft(token: string | undefined) {
  const modes: string[] = [];
  const get: MicrosoftToken = async (mode) => {
    modes.push(mode);
    return token;
  };
  return { get, modes };
}

suite("AzureDevOpsAuth", () => {
  const patKey = (host: string) => `smartPrReview.azureDevOps.pat:${host}`;

  test("sends a stored personal access token as Basic auth", async () => {
    const ms = microsoft("entra");
    const auth = new AzureDevOpsAuth("dev.azure.com", secrets({ [patKey("dev.azure.com")]: "abc" }), ms.get);
    assert.strictEqual(await auth.authorization(), `Basic ${Buffer.from(":abc").toString("base64")}`);
    assert.deepStrictEqual(ms.modes, []);
  });

  test("falls back to the Microsoft session on Azure DevOps Services", async () => {
    const ms = microsoft("entra");
    const auth = new AzureDevOpsAuth("dev.azure.com", secrets(), ms.get);
    assert.strictEqual(await auth.isSignedIn(), true);
    assert.strictEqual(await auth.authorization(), "Bearer entra");
    assert.ok(ms.modes.every((m) => m === "silent"));
  });

  test("never sends a Microsoft token to an Azure DevOps Server host", async () => {
    const ms = microsoft("entra");
    const auth = new AzureDevOpsAuth("tfs.contoso.com", secrets(), ms.get);
    assert.strictEqual(auth.cloud, false);
    assert.strictEqual(await auth.isSignedIn(), false);
    await assert.rejects(auth.authorization(), (e: unknown) => e instanceof HttpError && e.status === 401);
    assert.deepStrictEqual(ms.modes, []);
  });

  test("keeps personal access tokens per host", async () => {
    const store = secrets({ [patKey("tfs.contoso.com")]: "server-token" });
    assert.strictEqual(await new AzureDevOpsAuth("tfs.contoso.com", store, microsoft(undefined).get).isSignedIn(), true);
    assert.strictEqual(await new AzureDevOpsAuth("tfs.other.com", store, microsoft(undefined).get).isSignedIn(), false);
  });
});

suite("httpConsent", () => {
  const origin = "http://tfs.contoso.com:8080";

  /** The modal stub: answers, and counts how often it was shown. */
  function asker(answer: boolean) {
    const asked: string[] = [];
    const ask = async (o: string) => {
      asked.push(o);
      return answer;
    };
    return { ask, asked };
  }

  test("keeps the token when the user does not accept HTTP, and asks again on the next request", async () => {
    const no = asker(false);
    const auth = new AzureDevOpsAuth(
      "tfs.contoso.com",
      secrets({ [`smartPrReview.azureDevOps.pat:tfs.contoso.com`]: "abc" }),
      microsoft(undefined).get,
      httpConsent(origin, memento(), no.ask),
    );
    await assert.rejects(auth.authorization(), /was not sent: http:\/\/tfs\.contoso\.com:8080 uses HTTP/);
    await assert.rejects(auth.authorization());
    assert.deepStrictEqual(no.asked, [origin, origin]);
  });

  test("asks once for the requests made together, and remembers a yes for the server", async () => {
    const state = memento();
    const yes = asker(true);
    const consent = httpConsent(origin, state, yes.ask);
    await Promise.all([consent(), consent(), consent()]);
    await httpConsent(origin, state, yes.ask)();
    assert.deepStrictEqual(yes.asked, [origin]);
    // Another server asks for its own.
    await httpConsent("http://other:8080", state, yes.ask)();
    assert.deepStrictEqual(yes.asked, [origin, "http://other:8080"]);
  });
});

suite("recoveryActions", () => {
  const unauthorized = new HttpError(401, "Not authorized");

  test("offers a personal access token next to Microsoft sign-in on Azure DevOps Services", () => {
    // A Microsoft account outside the organization's tenant would otherwise loop on "Sign In".
    const auth = new AzureDevOpsAuth("dev.azure.com", secrets(), microsoft(undefined).get);
    assert.deepStrictEqual(recoveryActions(unauthorized, auth), ["signIn", "usePat"]);
  });

  test("offers only Sign In on GitHub and on Azure DevOps Server, whose sign-in already asks for a token", () => {
    assert.deepStrictEqual(recoveryActions(unauthorized, githubAuth), ["signIn"]);
    assert.deepStrictEqual(recoveryActions(unauthorized, new AzureDevOpsAuth("tfs.contoso.com", secrets(), microsoft(undefined).get)), ["signIn"]);
  });

  test("offers Retry for anything that is not a 401", () => {
    assert.deepStrictEqual(recoveryActions(new HttpError(500, "boom"), githubAuth), ["retry"]);
    assert.deepStrictEqual(recoveryActions(new Error("offline"), githubAuth), ["retry"]);
  });
});
