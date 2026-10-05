export interface StubResponse {
  status?: number;
  body?: unknown;
  headers?: Record<string, string>;
}

/** Replaces globalThis.fetch. Call restore() in teardown. */
export function stubFetch(handler: (url: string, init: RequestInit) => StubResponse) {
  const calls: { url: string; init: RequestInit }[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: string | URL | Request, init: RequestInit = {}) => {
    const url = String(input);
    calls.push({ url, init });
    const r = handler(url, init);
    return new Response(r.body === undefined ? null : JSON.stringify(r.body), { status: r.status ?? 200, headers: r.headers });
  }) as typeof fetch;
  return {
    calls,
    /** Parsed JSON body of the n-th request. */
    body: (n: number) => JSON.parse(String(calls[n].init.body)),
    restore: () => {
      globalThis.fetch = original;
    },
  };
}
