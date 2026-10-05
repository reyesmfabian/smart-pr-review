import { minimatch } from "minimatch";
import { readdir, readFile, realpath } from "node:fs/promises";
import { isAbsolute, join, relative, resolve, sep } from "node:path";
import { parse as parseYaml } from "yaml";
import type { ChangedFile } from "../git";
import * as git from "../git";

export interface Profile {
  name: string;
  description: string;
  /** Globs of repository paths the profile reviews; empty means every file. */
  include: string[];
  /** Review instructions: the Markdown after the frontmatter. */
  body: string;
  source: "repository" | "bundled";
  /** A repository profile whose working-copy content differs from the pull request base. */
  local: boolean;
}

export type ProfileContent = Pick<Profile, "name" | "description" | "include" | "body">;

/** Reads "---\n<yaml>\n---\n<body>". Undefined when there is no frontmatter or no name. */
export function parseProfile(text: string): ProfileContent | undefined {
  // Editors on Windows may save a byte order mark.
  const match = /^\uFEFF?---\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n([\s\S]*))?$/.exec(text);
  if (!match) {
    return undefined;
  }
  let data: unknown;
  try {
    data = parseYaml(match[1]);
  } catch {
    return undefined;
  }
  if (typeof data !== "object" || data === null) {
    return undefined;
  }
  const { name, description, include } = data as Record<string, unknown>;
  if (typeof name !== "string" || !name.trim()) {
    return undefined;
  }
  const globs = typeof include === "string" ? [include] : Array.isArray(include) ? include.filter((g): g is string => typeof g === "string") : [];
  return { name: name.trim(), description: typeof description === "string" ? description.trim() : "", include: globs, body: (match[2] ?? "").trim() };
}

export function appliesTo(profile: Pick<Profile, "include">, path: string): boolean {
  return profile.include.length === 0 || profile.include.some((glob) => minimatch(path, glob, { dot: true }));
}

export interface ProfileSources {
  repoRoot: string;
  /** Repository-relative folder, from the setting smartPrReview.profilesPath. */
  folder: string;
  /** Commit the pull request starts from. */
  baseSha: string;
  /** Files the pull request changes. */
  changed: readonly ChangedFile[];
  /** Folder with the profiles that ship with the extension. */
  bundledDir: string;
}

/**
 * Repository profiles come from the working copy, so local edits apply at once, except the ones the pull
 * request touches: those it adds are ignored and those it modifies, renames or deletes are read at the
 * base, so a pull request cannot change the profile that reviews it. Bundled profiles fill in the names
 * the repository does not define.
 */
export async function loadProfiles(s: ProfileSources): Promise<Profile[]> {
  const repository = await repositoryProfiles(s);
  const names = new Set(repository.map((p) => p.name));
  const bundled = await Promise.all((await markdownFiles(s.bundledDir)).map((file) => file.read()));
  const extra = bundled
    .map(parseProfile)
    .filter((p): p is ProfileContent => p !== undefined && !names.has(p.name))
    .map((p): Profile => ({ ...p, source: "bundled", local: false }));
  return [...repository, ...extra];
}

async function repositoryProfiles(s: ProfileSources): Promise<Profile[]> {
  const absolute = resolve(s.repoRoot, s.folder);
  const inside = relative(s.repoRoot, absolute);
  // The setting can come from the repository's .vscode/settings.json: never read outside the repository.
  if (inside === ".." || inside.startsWith(`..${sep}`) || isAbsolute(inside)) {
    return [];
  }
  // One spelling for the folder, as git writes paths, so the pull request's changes are recognized.
  const folder = inside.split(sep).join("/");
  const prefix = folder ? `${folder}/` : "";
  // A folder that resolves somewhere else (a symlink the pull request may have added) is read at the base only.
  const real = await realpath(absolute).catch(() => undefined);
  const expected = join(await realpath(s.repoRoot), inside);
  const working = new Map(real === expected ? (await markdownFiles(absolute)).map((f) => [`${prefix}${f.name}`, f.read]) : []);
  const listing = await git.git(s.repoRoot, ["ls-tree", "-z", "--name-only", s.baseSha, "--", prefix || "."]).catch(() => "");
  const atBase = new Set(listing.split("\0").filter((p) => p.endsWith(".md") && !p.slice(prefix.length).includes("/")));
  const addedByPullRequest = new Set(s.changed.filter((f) => f.status === "A" || f.status === "R").map((f) => f.path));
  const changedByPullRequest = new Set(s.changed.flatMap((f) => (f.status === "A" ? [] : [f.oldPath ?? f.path])));

  const profiles: Profile[] = [];
  for (const path of [...new Set([...working.keys(), ...atBase])].sort()) {
    if (addedByPullRequest.has(path) && !atBase.has(path)) {
      continue;
    }
    const base = atBase.has(path) ? await git.showFile(s.repoRoot, s.baseSha, path) : undefined;
    const read = working.get(path);
    const text = changedByPullRequest.has(path) || !read ? base : await read();
    const parsed = text === undefined ? undefined : parseProfile(text);
    if (parsed) {
      profiles.push({ ...parsed, source: "repository", local: text !== base });
    }
  }
  return profiles;
}

async function markdownFiles(dir: string): Promise<{ name: string; read: () => Promise<string> }[]> {
  const entries = await readdir(dir, { withFileTypes: true }).catch(() => []);
  return entries.filter((e) => e.isFile() && e.name.endsWith(".md")).map((e) => ({ name: e.name, read: () => readFile(join(dir, e.name), "utf8") }));
}
