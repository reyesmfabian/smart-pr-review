export type ProviderKind = "github" | "azureDevOps";

export interface RemoteInfo {
  kind: ProviderKind;
  /** API host: github.com, dev.azure.com, or the Azure DevOps Server host name. */
  host: string;
  /** Web origin the REST calls go to, e.g. https://dev.azure.com or http://tfs.contoso.com:8080. */
  origin: string;
  /** GitHub owner, Azure DevOps organization, or Azure DevOps Server collection path. */
  owner: string;
  /** Azure DevOps project. */
  project?: string;
  repo: string;
}

export function parseRemote(raw: string, azureDevOpsHosts: readonly string[]): RemoteInfo | undefined {
  const url = toUrl(raw.trim());
  if (!url) {
    return undefined;
  }
  const host = url.hostname.toLowerCase();
  const parts = url.pathname.split("/").filter(Boolean).map(decode);
  if (parts.length > 0) {
    parts[parts.length - 1] = parts[parts.length - 1].replace(/\.git$/, "");
  }

  if (host === "github.com") {
    return parts.length >= 2 ? { kind: "github", host, origin: "https://github.com", owner: parts[0], repo: parts[1] } : undefined;
  }
  const cloud = { kind: "azureDevOps", host: "dev.azure.com", origin: "https://dev.azure.com" } as const;
  if (host === "ssh.dev.azure.com" || host === "vs-ssh.visualstudio.com") {
    // v3/{organization}/{project}/{repo}
    return parts.length === 4 && parts[0] === "v3" ? { ...cloud, owner: parts[1], project: parts[2], repo: parts[3] } : undefined;
  }

  // HTTPS Azure DevOps remotes: .../{project}/_git/{repo}
  const git = parts.indexOf("_git");
  if (git < 1 || git !== parts.length - 2) {
    return undefined;
  }
  const project = parts[git - 1];
  const repo = parts[git + 1];
  if (host === "dev.azure.com") {
    return git === 2 ? { ...cloud, owner: parts[0], project, repo } : undefined;
  }
  if (host.endsWith(".visualstudio.com")) {
    return { ...cloud, owner: host.split(".")[0], project, repo };
  }
  if (git >= 2 && azureDevOpsHosts.some((h) => h.toLowerCase() === host)) {
    // Keep the scheme and port of HTTP(S) remotes (on-premises servers often use http://host:8080); SSH remotes use HTTPS.
    const origin = url.protocol === "http:" || url.protocol === "https:" ? `${url.protocol}//${url.host}` : `https://${host}`;
    return { kind: "azureDevOps", host, origin, owner: parts.slice(0, git - 1).join("/"), project, repo };
  }
  return undefined;
}

function toUrl(raw: string): URL | undefined {
  // scp-like syntax: git@host:path
  const scp = /^[^@/]+@([^:/]+):(.+)$/.exec(raw);
  try {
    return new URL(scp ? `ssh://${scp[1]}/${scp[2]}` : raw);
  } catch {
    return undefined;
  }
}

function decode(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}
