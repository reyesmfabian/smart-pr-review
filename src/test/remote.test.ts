import * as assert from "node:assert/strict";
import { parseRemote, type RemoteInfo } from "../remote";

suite("parseRemote", () => {
  const github: RemoteInfo = { kind: "github", host: "github.com", origin: "https://github.com", owner: "octo", repo: "app" };
  const azure: RemoteInfo = {
    kind: "azureDevOps",
    host: "dev.azure.com",
    origin: "https://dev.azure.com",
    owner: "contoso",
    project: "My Project",
    repo: "web",
  };
  const server = (origin: string): RemoteInfo => ({
    kind: "azureDevOps",
    host: "tfs.contoso.com",
    origin,
    owner: "tfs/DefaultCollection",
    project: "Proj",
    repo: "web",
  });
  const cases: [string, RemoteInfo | undefined][] = [
    ["https://github.com/octo/app.git", github],
    ["git@github.com:octo/app.git", github],
    ["ssh://git@github.com/octo/app", github],
    ["https://contoso@dev.azure.com/contoso/My%20Project/_git/web", azure],
    ["git@ssh.dev.azure.com:v3/contoso/My%20Project/web", azure],
    ["https://contoso.visualstudio.com/DefaultCollection/My%20Project/_git/web", azure],
    ["https://contoso.visualstudio.com/My%20Project/_git/web", azure],
    ["contoso@vs-ssh.visualstudio.com:v3/contoso/My%20Project/web", azure],
    ["https://tfs.contoso.com/tfs/DefaultCollection/Proj/_git/web", server("https://tfs.contoso.com")],
    ["http://tfs.contoso.com:8080/tfs/DefaultCollection/Proj/_git/web", server("http://tfs.contoso.com:8080")],
    ["ssh://tfs.contoso.com:22/tfs/DefaultCollection/Proj/_git/web", server("https://tfs.contoso.com")],
    ["https://tfs.other.com/tfs/DefaultCollection/Proj/_git/web", undefined],
    ["https://gitlab.com/octo/app.git", undefined],
    ["not a url", undefined],
  ];

  for (const [url, expected] of cases) {
    test(url, () => {
      assert.deepStrictEqual(parseRemote(url, ["TFS.contoso.com"]), expected);
    });
  }
});
