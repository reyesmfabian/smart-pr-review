---
name: Performance
description: Hot paths, queries, memory and blocking work
exclude:
  - "**/package-lock.json"
  - "**/npm-shrinkwrap.json"
  - "**/pnpm-lock.yaml"
  - "**/packages.lock.json"
  - "**/gradle.lockfile"
  - "**/go.sum"
  - "**/*.lock"
  - "**/*.min.js"
  - "**/*.min.css"
  - "**/*.js.map"
  - "**/*.css.map"
  - "**/__snapshots__/**"
  - "**/*.snap"
---
Review the changes for performance problems that a user would notice.

- Work repeated inside loops: queries (N+1), network calls, file reads, regular expression compilation.
- Algorithms that become quadratic or worse on realistic data sizes.
- Blocking work on the main thread or event loop; missing pagination, streaming or timeouts.
- Memory: unbounded caches, large objects kept alive, listeners and timers that are never released.
- Missing indexes or full scans implied by new queries.

Say when a problem matters only at a scale the code is unlikely to reach.
