import { randomUUID } from "node:crypto";
import type * as vscode from "vscode";
import type { Draft } from "../providers/types";

/** Draft comments and the review summary of one pull request, persisted in workspace state. */
export class DraftStore {
  constructor(
    private readonly storage: vscode.Memento,
    /** e.g. "github:octo/app:7" */
    private readonly key: string,
  ) {}

  all(): Draft[] {
    return this.storage.get<Draft[]>(`drafts:${this.key}`, []);
  }

  async add(draft: Omit<Draft, "id">): Promise<Draft> {
    const created = { ...draft, id: randomUUID() };
    await this.save([...this.all(), created]);
    return created;
  }

  async update(id: string, body: string): Promise<void> {
    await this.save(this.all().map((d) => (d.id === id ? { ...d, body } : d)));
  }

  async remove(ids: readonly string[]): Promise<void> {
    const removed = new Set(ids);
    await this.save(this.all().filter((d) => !removed.has(d.id)));
  }

  summary(): string {
    return this.storage.get<string>(`summary:${this.key}`, "");
  }

  async setSummary(text: string): Promise<void> {
    await this.storage.update(`summary:${this.key}`, text);
  }

  private async save(drafts: Draft[]): Promise<void> {
    await this.storage.update(`drafts:${this.key}`, drafts);
  }
}
