import { copyFileSync, cpSync, rmSync } from "node:fs";

// Refresh generated assets so removed shared profiles cannot linger in the VSIX.
const profiles = new URL("../profiles/", import.meta.url);
rmSync(profiles, { recursive: true, force: true });
cpSync(new URL("../../profiles/", import.meta.url), profiles, { recursive: true });
copyFileSync(new URL("../../LICENSE", import.meta.url), new URL("../LICENSE", import.meta.url));
