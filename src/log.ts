import * as vscode from "vscode";

export const log = vscode.window.createOutputChannel("Smart PR Review", { log: true });

/** Raw AI answers that could not be read, and prompts at trace level. */
export const aiLog = vscode.window.createOutputChannel("Smart PR Review: AI", { log: true });
