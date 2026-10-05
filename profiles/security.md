---
name: Security
description: Injection, secrets, input validation, access control
---
Review the changes for security problems. For each one, say how it could be exploited.

- Injection: SQL, shell commands, paths, HTML or Markdown rendered without escaping, regular expressions built from input.
- Secrets: keys, tokens or passwords in code, logs, error messages or test fixtures.
- Input from users, files or the network used without validation or size limits.
- Authentication and authorization: missing checks, checks on the client only, privilege escalation.
- Unsafe defaults: disabled TLS verification, permissive CORS, debug flags, wide file permissions.
- New dependencies that are unmaintained or pulled from untrusted sources.
