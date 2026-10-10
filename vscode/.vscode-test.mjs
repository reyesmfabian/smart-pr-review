import { defineConfig } from "@vscode/test-cli";
import { tmpdir } from "node:os";
import { join } from "node:path";

export default defineConfig({
  files: process.env.SPR_UI_PREVIEW ? "out/test/ui.preview.js" : "out/test/**/*.test.js",
  // The engines.vscode floor: tests prove the extension works on the oldest supported VS Code.
  version: "1.95.0",
  // Short user-data dir: VS Code's IPC socket path must stay under 103 characters on macOS.
  launchArgs: [`--user-data-dir=${join(tmpdir(), process.env.SPR_UI_PREVIEW ? "spr-preview" : "spr-test")}`],
  mocha: { timeout: process.env.SPR_UI_PREVIEW ? 0 : 20000 },
});
