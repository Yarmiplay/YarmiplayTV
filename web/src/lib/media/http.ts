import { MediaSourceError, messageOf } from "./mediaSource";

/**
 * The HTTP plumbing JellyfinClient.kt and PlexClient.kt do with OkHttp, done with the browser's fetch. Both servers
 * answer CORS with `*`, so the page calls them directly; what the browser refuses is a plain-http server from a page
 * served over https (mixed content), which gets a clear message here instead of an opaque network error.
 */

/** The part of `fetch` the clients use; tests pass their own. */
export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

/** Identifies this app to the servers (shown in Jellyfin's dashboard and Quick Connect prompt, and Plex's devices). */
export interface ClientInfo {
  clientName: string;
  deviceName: string;
  deviceId: string;
  version: string;
}

export const clientInfo = (c: Omit<ClientInfo, "clientName"> & { clientName?: string }): ClientInfo => ({ clientName: "YarmiplayTV", ...c });

/** What both clients take besides their [ClientInfo]. */
export interface HttpOptions {
  fetch?: FetchLike;
  /**
   * The page's own protocol ("https:" or "http:"), to know which servers the browser will refuse; the page's
   * `location.protocol` by default.
   */
  pageProtocol?: string;
}

export const defaultFetch: FetchLike = (input, init) => globalThis.fetch(input, init);

export const currentPageProtocol = (): string => (globalThis as { location?: { protocol?: string } }).location?.protocol ?? "";

/** Query parameters in order; a name may repeat. */
export type Query = [string, string][];

/** [base] (which may have a path, like "/jellyfin") plus [path] and [query]; [path] is encoded unless [encodedPath]. */
export function buildUrl(base: string, path: string, query: Query = [], encodedPath = false): URL {
  let root: URL;
  try {
    root = new URL(base);
  } catch {
    throw new MediaSourceError(`Invalid server address: ${base}`);
  }
  const segments = path.replace(/^\/+/, "");
  const encoded = encodedPath ? segments : segments.split("/").map(encodeURIComponent).join("/");
  const url = new URL(root.href.replace(/\/+$/, "") + "/" + encoded);
  url.search = query.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("&");
  return url;
}

const LOOPBACK = new Set(["localhost", "127.0.0.1", "[::1]"]);

/**
 * Whether the browser refuses [url] from a page loaded over [pageProtocol]: plain http from an https page. Browsers
 * let such pages reach this computer's own loopback address, so that one isn't refused.
 */
export function blockedAsMixedContent(url: string | URL, pageProtocol: string): boolean {
  if (pageProtocol !== "https:") return false;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  return parsed.protocol === "http:" && !LOOPBACK.has(parsed.hostname) && !parsed.hostname.endsWith(".localhost");
}

/** The server answered, but not with JSON (a router's login page, a proxy's error page). */
export class NotJsonError extends MediaSourceError {
  /** The start of what came instead. */
  readonly snippet: string;

  constructor(text: string) {
    const snippet = text.trim().slice(0, 80);
    super(`The answer isn't JSON (${snippet})`);
    this.name = "NotJsonError";
    this.snippet = snippet;
  }
}

/** [text] parsed as the DTO [T]: every DTO field is optional, so whatever shape arrives is read defensively. */
export function parseJson<T>(text: string): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new NotJsonError(text);
  }
}

const hostPort = (url: URL) => `${url.hostname}:${url.port || (url.protocol === "https:" ? "443" : "80")}`;

/** [signal] that also gives up after [ms]. */
function withTimeout(ms: number, signal?: AbortSignal | null): AbortSignal {
  const timeout = AbortSignal.timeout(ms);
  if (!signal) return timeout;
  if (typeof AbortSignal.any === "function") return AbortSignal.any([signal, timeout]);
  const controller = new AbortController();
  const abort = (s: AbortSignal) => () => controller.abort(s.reason);
  signal.addEventListener("abort", abort(signal), { once: true });
  timeout.addEventListener("abort", abort(timeout), { once: true });
  return controller.signal;
}

export interface RequestOptions {
  method?: "GET" | "POST";
  headers?: Record<string, string>;
  body?: string;
  /** Gives up after this long (OkHttp's connect plus read timeouts, roughly). */
  timeoutMs?: number;
  signal?: AbortSignal | null;
  /** Why the browser won't reach a plain-http [url] from this https page; when null such requests are tried anyway. */
  insecureMessage?: ((url: URL) => string) | null;
  pageProtocol?: string;
}

/**
 * Fetches [url] and returns the body. A failed status throws a [MediaSourceError] with its code (401/403/404 worded
 * like the apps'), a network failure one without.
 */
export async function request(fetchFn: FetchLike, url: URL, opts: RequestOptions = {}): Promise<string> {
  if (opts.insecureMessage && blockedAsMixedContent(url, opts.pageProtocol ?? currentPageProtocol())) {
    throw new MediaSourceError(opts.insecureMessage(url));
  }
  let response: Response;
  try {
    response = await fetchFn(url.href, {
      method: opts.method ?? "GET",
      headers: opts.headers,
      body: opts.method === "POST" ? (opts.body ?? "") : undefined,
      signal: withTimeout(opts.timeoutMs ?? 30_000, opts.signal),
    });
  } catch (e) {
    if (opts.signal?.aborted) throw opts.signal.reason ?? e;
    const why = e instanceof DOMException && e.name === "TimeoutError" ? "timed out" : messageOf(e);
    throw new MediaSourceError(`Cannot reach ${hostPort(url)} (${why})`, null, { cause: e });
  }
  const text = await response.text().catch(() => "");
  if (!response.ok) {
    const code = response.status;
    const msg =
      code === 401
        ? "Not authorised (401)"
        : code === 403
          ? "Forbidden (403)"
          : code === 404
            ? `Not found (404): ${url.pathname}`
            : `HTTP ${code}: ${text.slice(0, 200)}`;
    throw new MediaSourceError(msg, code);
  }
  return text;
}

/** Waits [ms], or rejects with the signal's reason when [signal] aborts first. */
export function delay(ms: number, signal?: AbortSignal | null): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason);
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal?.reason);
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}
