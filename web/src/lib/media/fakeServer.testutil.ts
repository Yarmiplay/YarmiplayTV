import type { FetchLike } from "./http";

/** A request the fake server got. */
export interface Recorded {
  method: string;
  url: URL;
  headers: Headers;
  body: string;
}

/** What a route answers: JSON text (200), a status with an optional body, or "unreachable" for a network failure. */
export type Answer = string | { status: number; body?: string; contentType?: string } | "unreachable";

/**
 * A media server for tests, as MockWebServer is for the Kotlin's: [route] answers each request by its URL, and every
 * request is kept in order for the test to look at.
 */
export function fakeServer(route: (req: Recorded) => Answer) {
  const requests: Recorded[] = [];
  const fetch: FetchLike = async (input, init) => {
    const req: Recorded = {
      method: init?.method ?? "GET",
      url: new URL(input),
      headers: new Headers(init?.headers),
      body: typeof init?.body === "string" ? init.body : "",
    };
    requests.push(req);
    const answer = route(req);
    if (answer === "unreachable") throw new TypeError("fetch failed");
    if (typeof answer === "string") return new Response(answer, { status: 200, headers: { "Content-Type": "application/json" } });
    const empty = answer.status === 204 || answer.status === 304;
    return new Response(empty ? null : (answer.body ?? ""), {
      status: answer.status,
      headers: { "Content-Type": answer.contentType ?? "application/json" },
    });
  };
  return { fetch, requests };
}
