import * as assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";

const root = join(__dirname, "../..");

async function readJson(path: string): Promise<Record<string, string>> {
  return JSON.parse(await readFile(join(root, path), "utf8"));
}

suite("localization", () => {
  test("every localized string in src has a Spanish translation", async () => {
    const es = await readJson("l10n/bundle.l10n.es.json");
    const missing: string[] = [];
    const files = (await readdir(join(root, "src"), { recursive: true })).filter((f) => f.endsWith(".ts"));
    for (const file of files) {
      const source = await readFile(join(root, "src", file), "utf8");
      for (const match of source.matchAll(/l10n\.t\(\s*'((?:[^'\\]|\\.)*)'/g)) {
        const key = match[1].replace(/\\(.)/g, "$1");
        if (!(key in es)) {
          missing.push(`${file}: ${key}`);
        }
      }
    }
    assert.deepStrictEqual(missing, []);
  });

  test("every %placeholder% in package.json is translated", async () => {
    const manifest = await readFile(join(root, "package.json"), "utf8");
    const keys = [...new Set([...manifest.matchAll(/"%([\w.]+)%"/g)].map((m) => m[1]))];
    for (const nls of ["package.nls.json", "package.nls.es.json"]) {
      const strings = await readJson(nls);
      assert.deepStrictEqual(
        keys.filter((k) => !(k in strings)),
        [],
        nls,
      );
    }
  });
});
