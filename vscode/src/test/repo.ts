import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { git } from "../git";

export interface TestRepo {
  /** Reviewer clone: has main, but not the pull request commit until it is fetched. */
  root: string;
  /** Bare repository the clone fetches from. */
  remote: string;
  baseSha: string;
  headSha: string;
}

/**
 * A bare "remote" with main and refs/pull/1/head, plus a fresh clone to review from.
 * The pull request modifies src/app.ts line 5, adds "src/new file.ts", renames "docs/old name.md"
 * to "docs/new name.md" and deletes keep.txt.
 */
export async function makeRepo(): Promise<TestRepo> {
  const dir = await mkdtemp(join(tmpdir(), "smart-pr-review-"));
  const remote = join(dir, "remote.git");
  const author = join(dir, "author");
  const root = join(dir, "work");
  await git(dir, ["init", "--quiet", "--bare", "--initial-branch=main", remote]);
  await git(dir, ["init", "--quiet", "--initial-branch=main", author]);
  await configure(author);
  await git(author, ["remote", "add", "origin", remote]);

  const lines = Array.from({ length: 10 }, (_, i) => `line ${i + 1}`);
  await write(author, "src/app.ts", `${lines.join("\n")}\n`);
  await write(author, "docs/old name.md", "# Docs\n\nThis file is renamed by the pull request.\n");
  await write(author, "keep.txt", "Deleted by the pull request.\n");
  const baseSha = await commit(author, "base");
  await git(author, ["push", "--quiet", "origin", "main"]);

  lines[4] = "changed line 5";
  await write(author, "src/app.ts", `${lines.join("\n")}\n`);
  await write(author, "src/new file.ts", "export const added = true;\n");
  await git(author, ["mv", "docs/old name.md", "docs/new name.md"]);
  await git(author, ["rm", "--quiet", "keep.txt"]);
  const headSha = await commit(author, "head");
  await git(author, ["push", "--quiet", "origin", "HEAD:refs/pull/1/head"]);

  // --no-local: a local clone would copy every object, including the unfetched pull request commit.
  await git(dir, ["clone", "--quiet", "--no-local", remote, root]);
  await configure(root);
  return { root, remote, baseSha, headSha };
}

async function configure(repo: string): Promise<void> {
  await git(repo, ["config", "user.name", "Test"]);
  await git(repo, ["config", "user.email", "test@example.com"]);
  await git(repo, ["config", "commit.gpgsign", "false"]);
}

async function write(repo: string, path: string, content: string): Promise<void> {
  await mkdir(dirname(join(repo, path)), { recursive: true });
  await writeFile(join(repo, path), content);
}

async function commit(repo: string, message: string): Promise<string> {
  await git(repo, ["add", "--all"]);
  await git(repo, ["commit", "--quiet", "-m", message]);
  return (await git(repo, ["rev-parse", "HEAD"])).trim();
}
