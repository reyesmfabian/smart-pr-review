---
name: General
description: Correctness, clarity and tests
---
Review the changes the way a careful senior engineer would.

- Bugs: wrong conditions, off-by-one errors, missing awaits, unhandled errors and null or undefined values, broken edge cases (empty input, very large input, concurrent calls).
- Behavior changes the description does not mention, and callers that the change breaks (comment on the changed line that breaks them).
- Tests: new behavior without a test, tests that cannot fail, removed assertions.
- Clarity: names that mislead, dead code, duplicated logic, comments that no longer match the code.

Report only real problems with a concrete consequence. Do not comment on formatting a linter would fix.
