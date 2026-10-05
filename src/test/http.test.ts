import * as assert from "node:assert/strict";
import { HttpError, requestJson } from "../http";
import { stubFetch } from "./fetch";

suite("requestJson", () => {
  let fetchStub: ReturnType<typeof stubFetch> | undefined;

  teardown(() => fetchStub?.restore());

  test("treats the Azure DevOps 203 sign-in page as 401, so the user is asked to sign in again", async () => {
    fetchStub = stubFetch(() => ({ status: 203, body: "<html>Sign in</html>" }));
    await assert.rejects(
      requestJson("https://dev.azure.com/contoso/_apis/connectionData"),
      (e: unknown) => e instanceof HttpError && e.status === 401,
    );
  });

  test("treats an HTML page answered with 200 (a proxy or SSO sign-in in front of a server) as 401", async () => {
    fetchStub = stubFetch(() => ({ status: 200, body: "<html>Sign in</html>", headers: { "content-type": "text/html; charset=utf-8" } }));
    await assert.rejects(requestJson("https://tfs.contoso.com/tfs/_apis/connectionData"), (e: unknown) => e instanceof HttpError && e.status === 401);
  });
});
