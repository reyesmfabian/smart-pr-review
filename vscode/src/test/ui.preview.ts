/** Opt-in interactive demo: SPR_UI_PREVIEW=1 npm test. No provider calls or AI spending. */
import * as vscode from "vscode";
import { CommentsManager, type DraftComment } from "../review/comments";
import { openFileDiff, RevisionContentProvider, SCHEME } from "../review/diff";
import { ReviewWindow } from "../review/reviewWindow";
import { ReviewSession } from "../review/session";
import { FakeProvider, memento } from "./fakes";
import { makeRepo } from "./repo";

suite("interactive UI preview (local demo)", () => {
  test("keeps the review open until its navigator is closed", async () => {
    const repo = await makeRepo();
    const provider = new FakeProvider(repo);
    const getPr = provider.getPullRequest.bind(provider);
    provider.getPullRequest = async (id) => ({
      ...(await getPr(id)),
      title: "[DEMO local] Validación de solicitudes y manejo de errores",
      body: "Vista previa con datos de ejemplo. No está conectada a GitHub ni Azure DevOps.\n\nObjetivo: validar entradas antes de procesarlas y conservar las respuestas de error.\n\nPrueba las pestañas, los filtros, los checks de archivos y Preparar revisión. Publicar solo modifica este proveedor en memoria.",
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
    const resources: vscode.Disposable[] = [
      session,
      comments,
      vscode.workspace.registerTextDocumentContentProvider(SCHEME, new RevisionContentProvider(repo.root)),
      vscode.commands.registerCommand("smartPrReview.reviewWithAI", () =>
        vscode.window.showInformationMessage("Demo local: los borradores de IA ya están cargados; no se llama a ningún motor."),
      ),
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
      await ReviewWindow.show(session, memento());
      const panel = (ReviewWindow as unknown as { instance: { panel: vscode.WebviewPanel } }).instance.panel;
      const closed = new Promise<void>((resolve) => panel.onDidDispose(() => resolve()));
      await openFileDiff(session, session.require().files.find((f) => f.path === "src/app.ts")!, 5, vscode.ViewColumn.Beside);
      console.log("UI PREVIEW READY — close the review navigator to finish.");
      await closed;
    } finally {
      session.close();
      resources.reverse().forEach((d) => d.dispose());
    }
  });
});
