import { vi } from 'vitest';

export interface Call {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: unknown;
}

type Handler = (call: Call) => { status?: number; body?: unknown } | unknown;

/** "GET /admin/overview" 같은 키로 응답을 정한다. 쿼리 문자열은 키에서 뺀다. */
export function fakeFetch(routes: Record<string, Handler>) {
  const calls: Call[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const call: Call = { method, url, headers, body: init?.body ? JSON.parse(String(init.body)) : undefined };
    calls.push(call);
    const handler = routes[`${method} ${url.split('?')[0]}`];
    if (!handler) return new Response('not found', { status: 404 });
    const out = handler(call);
    const res = out && typeof out === 'object' && 'body' in out ? (out as { status?: number; body?: unknown }) : { body: out };
    return new Response(res.body === undefined ? '' : JSON.stringify(res.body), { status: res.status ?? 200 });
  });
  vi.stubGlobal('fetch', fn);
  return { fn, calls };
}
