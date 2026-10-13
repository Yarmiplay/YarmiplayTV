/** A request a test's fake server saw. */
export interface SeenRequest {
  method: string;
  url: URL;
  /** Header names in lower case. */
  headers: Record<string, string>;
  body: string;
}

/** A fake server's answer: a status with a body (JSON when it's an object). */
export interface FakeAnswer {
  status?: number;
  body?: string | object;
}

/**
 * `fetch` answered by [handle] in memory, for tests (never the network); every request is kept in [requests]. A
 * handler that throws is a request that couldn't be sent.
 */
export function fakeFetch(handle: (request: SeenRequest) => FakeAnswer) {
  const requests: SeenRequest[] = [];
  const fetch = async (url: string, init: RequestInit = {}): Promise<Response> => {
    const headers: Record<string, string> = {};
    new Headers(init.headers).forEach((value, key) => (headers[key] = value));
    const request: SeenRequest = {
      method: (init.method ?? "GET").toUpperCase(),
      url: new URL(url),
      headers,
      body: typeof init.body === "string" ? init.body : init.body == null ? "" : String(init.body),
    };
    requests.push(request);
    const answer = handle(request);
    const status = answer.status ?? 200;
    const json = typeof answer.body === "object";
    const body: string = typeof answer.body === "object" ? JSON.stringify(answer.body) : (answer.body ?? "");
    return new Response(status === 204 ? null : body, { status, headers: { "Content-Type": json ? "application/json" : "text/plain" } });
  };
  return { fetch, requests };
}
