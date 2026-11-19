/**
 * Opt-in interactive demo: SPR_UI_PREVIEW=1 npm test. No provider calls or AI spending.
 * SPR_UI_PREVIEW=ai: a pull request with real bugs and a working "Review with AI", for trying engines (a local model
 * by URL, say) without a GitHub or Azure DevOps pull request. Set the engines in this window's user settings.
 */
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import * as vscode from "vscode";
import { reviewWithAi, setEngineApiKey } from "../ai/commands";
import { CommentsManager, type DraftComment } from "../review/comments";
import { openFileDiff, RevisionContentProvider, SCHEME } from "../review/diff";
import { ReviewWindow } from "../review/reviewWindow";
import { ReviewSession } from "../review/session";
import { FakeProvider, memento, secrets } from "./fakes";
import { makeRepo } from "./repo";

const AI = process.env.SPR_UI_PREVIEW === "ai";

const USERS_BASE = `import { db } from "../db";

export interface User {
  id: string;
  email: string;
  role: "admin" | "member";
}

export async function getUser(id: string): Promise<User | undefined> {
  const rows = await db.query("SELECT id, email, role FROM users WHERE id = $1", [id]);
  return rows[0];
}
`;

/** What the pull request adds: SQL injection, a missing permission check and await, logging, an N+1 query. */
const USERS_HEAD = `${USERS_BASE}
export async function searchUsers(term: string, limit: string): Promise<User[]> {
  const max = parseInt(limit);
  return db.query(\`SELECT id, email, role FROM users WHERE email LIKE '%\${term}%' LIMIT \${max}\`);
}

export async function deleteUser(requester: User, id: string): Promise<void> {
  console.log(\`Deleting user \${id} requested by \${requester.email}\`);
  db.query("DELETE FROM users WHERE id = $1", [id]);
}

export async function usersWithTeams(ids: string[]): Promise<Array<User & { team: string }>> {
  const result = [];
  for (const id of ids) {
    const user = await getUser(id);
    const team = await db.query("SELECT name FROM teams WHERE user_id = $1", [id]);
    result.push({ ...user!, team: team[0].name });
  }
  return result;
}
`;

const DB = `export const db = {
  async query(sql: string, params: unknown[] = []): Promise<any[]> {
    return [];
  },
};
`;

suite("interactive UI preview (local demo)", () => {
  test("keeps the review open until its navigator is closed", async () => {
    const repo = AI
      ? await makeRepo({ base: { "src/db.ts": DB, "src/api/users.ts": USERS_BASE }, head: { "src/api/users.ts": USERS_HEAD } })
      : await makeRepo();
    const provider = new FakeProvider(repo);
    const getPr = provider.getPullRequest.bind(provider);
    provider.getPullRequest = async (id) => ({
      ...(await getPr(id)),
      ...(AI
        ? {
            title: "[DEMO IA local] Búsqueda y borrado de usuarios",
            body: "Pull request de ejemplo con errores reales para probar Revisar con IA. No está conectado a GitHub ni Azure DevOps.\n\nAgrega búsqueda de usuarios por email, borrado de usuarios y la consulta de usuarios con sus equipos.",
          }
        : {
            title: "[DEMO local] Validación de solicitudes y manejo de errores",
            body: "Vista previa con datos de ejemplo. No está conectada a GitHub ni Azure DevOps.\n\nObjetivo: validar entradas antes de procesarlas y conservar las respuestas de error.\n\nPrueba las pestañas, los filtros, los checks de archivos y Preparar revisión. Publicar solo modifica este proveedor en memoria.",
          }),
    });
    provider.threads = [
      {
        id: "T1",
        path: "src/app.ts",
        side: "head",
        line: 5,
        isOutdated: false,
        isResolved: false,
        comments: [{ id: "C1", author: "Ana", body: "¿Podemos cubrir también las solicitudes sin identificador?", createdAt: "" }],
      },
      {
        id: "T2",
        path: "src/new file.ts",
        side: "head",
        line: 1,
        isOutdated: false,
        isResolved: true,
        comments: [{ id: "C2", author: "Luis", body: "El nombre exportado ya coincide con el contrato.", createdAt: "" }],
      },
    ];
    provider.setThreadResolved = async (_pr, id, resolved) => {
      const thread = provider.threads.find((t) => t.id === id);
      if (thread) {
        thread.isResolved = resolved;
      }
    };
    const session = new ReviewSession(provider, repo.root, "origin", memento(), "demo:local");
    const comments = new CommentsManager(session);
    // The API key lives only as long as the demo.
    const keys = secrets();
    const state = memento();
    const resources: vscode.Disposable[] = [
      session,
      comments,
      vscode.workspace.registerTextDocumentContentProvider(SCHEME, new RevisionContentProvider(repo.root)),
      AI
        ? vscode.commands.registerCommand("smartPrReview.reviewWithAI", async () =>
            reviewWithAi(session, join(__dirname, "../.."), await mkdtemp(join(tmpdir(), "spr-ai-")), state, keys),
          )
        : vscode.commands.registerCommand("smartPrReview.reviewWithAI", () =>
            vscode.window.showInformationMessage("Demo local: los borradores de IA ya están cargados; no se llama a ningún motor."),
          ),
      vscode.commands.registerCommand("smartPrReview.setEngineApiKey", () => setEngineApiKey(keys)),
      vscode.commands.registerCommand("smartPrReview.openInBrowser", () => vscode.window.showInformationMessage("Demo local: no hay un PR remoto.")),
      vscode.commands.registerCommand("smartPrReview.editDraft", (comment: DraftComment) => comments.editDraft(comment)),
      vscode.commands.registerCommand("smartPrReview.saveDraft", (comment: DraftComment) => comments.saveDraft(comment)),
      vscode.commands.registerCommand("smartPrReview.cancelEditDraft", () => comments.cancelEditDraft()),
      vscode.commands.registerCommand("smartPrReview.deleteDraft", (comment: DraftComment) => comments.deleteDraft(comment)),
      vscode.commands.registerCommand("smartPrReview.createDraft", (reply: vscode.CommentReply) => comments.createDraft(reply)),
      vscode.commands.registerCommand("smartPrReview.createSuggestion", (reply: vscode.CommentReply) => comments.createSuggestion(reply)),
      vscode.commands.registerCommand("smartPrReview.resolveThread", (thread: vscode.CommentThread) => comments.setResolved(thread, true)),
      vscode.commands.registerCommand("smartPrReview.reopenThread", (thread: vscode.CommentThread) => comments.setResolved(thread, false)),
    ];
    try {
      await session.open(1);
      // With AI, the drafts are the engine's; "Set API Key of an AI Engine" shows in the palette once a provider is set.
      if (AI) {
        await vscode.commands.executeCommand("setContext", "smartPrReview.provider", "demo");
      } else {
        await addDemoDrafts(session);
      }
      await ReviewWindow.show(session, memento());
      const panel = (ReviewWindow as unknown as { instance: { panel: vscode.WebviewPanel } }).instance.panel;
      const closed = new Promise<void>((resolve) => panel.onDidDispose(() => resolve()));
      const [path, line] = AI ? ["src/api/users.ts", 14] : ["src/app.ts", 5];
      await openFileDiff(session, session.require().files.find((f) => f.path === path)!, line, vscode.ViewColumn.Beside);
      console.log("UI PREVIEW READY — close the review navigator to finish.");
      await closed;
    } finally {
      session.close();
      resources.reverse().forEach((d) => d.dispose());
    }
  });
});

async function addDemoDrafts(session: ReviewSession): Promise<void> {
  await session.addDraft(
    "src/app.ts",
    5,
    5,
    "Validar el identificador antes de procesar la solicitud. Si falta, el acceso a sus propiedades puede fallar.",
    { profile: "Security", engine: "demo", severity: "error" },
  );
  await session.addDraft(
    "src/new file.ts",
    1,
    1,
    "Evitar repetir la consulta en cada render. Reutilizar el resultado mientras no cambien los parámetros.",
    { profile: "Performance", engine: "demo", severity: "warning" },
  );
  await session.addDraft("src/app.ts", 5, 5, "Añadir una prueba para el caso sin identificador.");
  await session.appendSummary("Revisión de ejemplo: validar las entradas y añadir cobertura para los casos de error.");
}
