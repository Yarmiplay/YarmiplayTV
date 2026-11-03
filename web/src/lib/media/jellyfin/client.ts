import {
  buildUrl,
  currentPageProtocol,
  defaultFetch,
  delay,
  parseJson,
  request,
  type ClientInfo,
  type FetchLike,
  type HttpOptions,
  type Query,
} from "../http";
import { MediaSourceError } from "../mediaSource";
import type { AuthenticateByNameBody, AuthenticationResult, PublicSystemInfo, QuickConnectAuthBody, QuickConnectResult } from "./dtos";

/**
 * A logged-in Jellyfin session; persist this to skip login next time. A plain object with JellyfinSession.kt's
 * field names, so the `jf_servers` list reads the same as the apps'.
 */
export interface JellyfinSession {
  serverUrl: string;
  serverName: string;
  serverId: string;
  userId: string;
  userName: string;
  accessToken: string;
  /** The Syncplay server whose host shares this Jellyfin with us; empty for servers the user signed in to. */
  sharedBy: string;
  /** Every address the sharing host gave for it, tried again when [serverUrl] stops answering. */
  candidateUrls: string[];
  /** The host stopped sharing it: its guest account answers 401. */
  noLongerShared: boolean;
  /**
   * Try `https:` on this host/port before [serverUrl] when [serverUrl] is plain `http:` (ambiguous after 2.9.1
   * rewrote some saved HTTPS entries to HTTP).
   */
  preferHttps: boolean;
}

/** A [JellyfinSession] with the defaults for its sharing fields. */
export const jellyfinSession = (
  s: Pick<JellyfinSession, "serverUrl" | "serverName" | "serverId" | "userId" | "userName" | "accessToken"> & Partial<JellyfinSession>,
): JellyfinSession => ({ sharedBy: "", candidateUrls: [], noLongerShared: false, preferHttps: false, ...s });

export const isShared = (s: JellyfinSession): boolean => s.sharedBy !== "";

export interface QuickConnectOptions {
  /** Called once with the code to show: the user approves it in another Jellyfin client (Settings → Quick Connect). */
  onCode(code: string): void;
  /** Aborting it stops the polling; the returned promise rejects with the signal's reason. */
  signal?: AbortSignal;
  pollIntervalMs?: number;
}

/** The message for a plain-http Jellyfin the browser won't reach from this https page. */
export const insecureJellyfinMessage = (url: URL): string =>
  `This page is served over HTTPS, so the browser won't connect to Jellyfin at ${url.origin}. ` +
  "Use the server's https:// address, or have the room's host share it through YarmiplayServerTV.";

/**
 * Minimal Jellyfin REST client (10.8+), as JellyfinClient.kt. Uses only long-stable endpoints so it keeps working
 * across server versions without pulling in the full SDK.
 */
export class JellyfinClient {
  private readonly fetch: FetchLike;
  private readonly pageProtocol: () => string;

  constructor(
    readonly clientInfo: ClientInfo,
    opts: HttpOptions = {},
  ) {
    this.fetch = opts.fetch ?? defaultFetch;
    this.pageProtocol = opts.pageProtocol !== undefined ? () => opts.pageProtocol ?? "" : currentPageProtocol;
  }

  authorizationHeader(token: string | null): string {
    const escape = (v: string) => v.replace(/"/g, "'").replace(/\n/g, " ");
    const c = this.clientInfo;
    let header = `MediaBrowser Client="${escape(c.clientName)}", Device="${escape(c.deviceName)}", DeviceId="${escape(c.deviceId)}", Version="${escape(c.version)}"`;
    if (token !== null) header += `, Token="${token}"`;
    return header;
  }

  /** Accepts "host", "host:port", "http(s)://host[:port][/base]" and returns a canonical base URL. */
  normalizeServerUrl(input: string): string {
    let s = input.trim().replace(/\/+$/, "");
    if (s === "") throw new MediaSourceError("Enter a server address");
    if (!/^https?:\/\//i.test(s)) s = `http://${s}`;
    let url: URL;
    try {
      url = new URL(s);
    } catch {
      throw new MediaSourceError(`Invalid server address: ${input}`);
    }
    if (!url.hostname) throw new MediaSourceError(`Invalid server address: ${input}`);
    if (!input.includes("://") && !/:\d+/.test(input)) url.port = "8096";
    return url.href.replace(/\/+$/, "");
  }

  publicInfo(serverUrl: string): Promise<PublicSystemInfo> {
    return this.get(serverUrl, null, "System/Info/Public");
  }

  async quickConnectEnabled(serverUrl: string): Promise<boolean> {
    try {
      return (await this.getRaw(serverUrl, null, "QuickConnect/Enabled")).trim() === "true";
    } catch {
      return false;
    }
  }

  /**
   * A URL that answers right now: HTTPS first when [input] (or [preferHttps]) calls for it, then plain HTTP on
   * the same host/port only after a plain-HTTP TLS handshake error. Callers that sign in should persist
   * [preferredServerUrl], not this.
   */
  async resolveReachableUrl(input: string, preferHttps = false): Promise<string> {
    const normalized = this.normalizeServerUrl(input);
    const httpsFirst = /^https:/i.test(normalized) ? normalized : preferHttps ? httpsUrl(normalized) : null;
    if (!httpsFirst) return normalized;
    try {
      await this.publicInfo(httpsFirst);
      return httpsFirst;
    } catch (httpsErr) {
      if (!isPlainHttpSslFailure(httpsErr)) throw httpsErr;
      const http = plainHttpUrl(httpsFirst);
      if (!http) throw httpsErr;
      try {
        await this.publicInfo(http);
        return http;
      } catch (httpErr) {
        throw bothSchemesFailed(httpsFirst, httpsErr, http, httpErr);
      }
    }
  }

  /** The URL to keep on a signed-in session (user's preferred scheme). */
  preferredServerUrl(input: string): string {
    return this.normalizeServerUrl(input);
  }

  /**
   * When [error] means the probe URL speaks plain HTTP, the same host over `http:` if it still validates.
   * Session-only — does not change what should be saved.
   */
  async reachablePlainHttp(session: JellyfinSession, error: unknown): Promise<string | null> {
    if (!isPlainHttpSslFailure(error)) return null;
    const probe = preferredProbeUrl(session);
    const http =
      plainHttpUrl(probe) ??
      plainHttpUrl(session.serverUrl) ??
      (/^http:/i.test(session.serverUrl) ? session.serverUrl.replace(/\/+$/, "") : null);
    if (!http) return null;
    try {
      return (await this.validateAt(http, session.accessToken)) ? http : null;
    } catch {
      return null;
    }
  }

  /** @deprecated Use [reachablePlainHttp]. */
  async sessionOnPlainHttp(session: JellyfinSession, error: unknown): Promise<JellyfinSession | null> {
    const http = await this.reachablePlainHttp(session, error);
    return http ? jellyfinSession({ ...session, serverUrl: http }) : null;
  }

  async quickConnect(serverUrl: string, opts: QuickConnectOptions): Promise<JellyfinSession> {
    const { signal } = opts;
    const preferred = this.preferredServerUrl(serverUrl);
    const base = await this.resolveReachableUrl(preferred);
    const info = await this.publicInfo(base);
    let initiated: QuickConnectResult;
    try {
      initiated = await this.post(base, null, "QuickConnect/Initiate", null, [], signal);
    } catch (e) {
      if (!(e instanceof MediaSourceError)) throw e;
      if (e.httpCode === 401 || e.httpCode === 403) throw new MediaSourceError("Quick Connect is disabled on this server", e.httpCode);
      if (e.httpCode !== 405 && e.httpCode !== 404) throw e;
      initiated = await this.get(base, null, "QuickConnect/Initiate", [], signal);
    }
    const secret = initiated.Secret ?? "";
    opts.onCode(initiated.Code ?? "");
    for (;;) {
      await delay(opts.pollIntervalMs ?? 2500, signal);
      const status: QuickConnectResult = await this.get(base, null, "QuickConnect/Connect", [["secret", secret]], signal);
      if (status.Authenticated === true) break;
    }
    const body: QuickConnectAuthBody = { Secret: secret };
    const auth: AuthenticationResult = await this.post(base, null, "Users/AuthenticateWithQuickConnect", JSON.stringify(body), [], signal);
    return sessionFrom(preferred, info, auth);
  }

  async login(serverUrl: string, username: string, password: string): Promise<JellyfinSession> {
    const preferred = this.preferredServerUrl(serverUrl);
    const base = await this.resolveReachableUrl(preferred);
    const info = await this.publicInfo(base);
    const body: AuthenticateByNameBody = { Username: username, Pw: password };
    let auth: AuthenticationResult;
    try {
      auth = await this.post(base, null, "Users/AuthenticateByName", JSON.stringify(body));
    } catch (e) {
      if (e instanceof MediaSourceError && e.httpCode === 401) throw new MediaSourceError("Wrong username or password", 401);
      throw e;
    }
    return sessionFrom(preferred, info, auth);
  }

  /** Checks a stored session is still valid at its preferred probe URL. */
  async validate(session: JellyfinSession): Promise<boolean> {
    return this.validateAt(preferredProbeUrl(session), session.accessToken);
  }

  async validateAt(serverUrl: string, accessToken: string): Promise<boolean> {
    try {
      await this.getRaw(serverUrl, accessToken, "Users/Me");
      return true;
    } catch (e) {
      if (e instanceof MediaSourceError && (e.httpCode === 401 || e.httpCode === 403)) return false;
      throw e;
    }
  }

  async logout(session: JellyfinSession): Promise<void> {
    const url = preferredProbeUrl(session);
    await this.postRaw(url, session.accessToken, "Sessions/Logout", null).catch(() => {});
    const http = plainHttpUrl(url);
    if (http && http !== url) await this.postRaw(http, session.accessToken, "Sessions/Logout", null).catch(() => {});
  }

  // --- HTTP plumbing ------------------------------------------------------------------

  url(base: string, path: string, query: Query = []): URL {
    return buildUrl(base, path, query);
  }

  async get<T>(base: string, token: string | null, path: string, query: Query = [], signal?: AbortSignal): Promise<T> {
    return parseJson<T>(await this.getRaw(base, token, path, query, signal));
  }

  async post<T>(base: string, token: string | null, path: string, body: string | null, query: Query = [], signal?: AbortSignal): Promise<T> {
    return parseJson<T>(await this.postRaw(base, token, path, body, query, signal));
  }

  getRaw(base: string, token: string | null, path: string, query: Query = [], signal?: AbortSignal): Promise<string> {
    return this.execute("GET", this.url(base, path, query), token, null, signal);
  }

  postRaw(base: string, token: string | null, path: string, body: string | null, query: Query = [], signal?: AbortSignal): Promise<string> {
    return this.execute("POST", this.url(base, path, query), token, body, signal);
  }

  private execute(method: "GET" | "POST", url: URL, token: string | null, body: string | null, signal?: AbortSignal): Promise<string> {
    const headers: Record<string, string> = { Authorization: this.authorizationHeader(token), Accept: "application/json" };
    if (method === "POST") headers["Content-Type"] = "application/json";
    return request(this.fetch, url, {
      method,
      headers,
      body: body ?? "",
      signal,
      insecureMessage: insecureJellyfinMessage,
      pageProtocol: this.pageProtocol(),
    });
  }
}

function sessionFrom(base: string, info: PublicSystemInfo, auth: AuthenticationResult): JellyfinSession {
  const user = auth.User;
  if (!user) throw new MediaSourceError("Server returned no user");
  const token = auth.AccessToken;
  if (!token) throw new MediaSourceError("Server returned no access token");
  return jellyfinSession({
    serverUrl: base,
    serverName: info.ServerName ?? base,
    serverId: auth.ServerId ?? info.Id ?? "",
    userId: user.Id,
    userName: user.Name ?? "",
    accessToken: token,
  });
}

const PLAIN_HTTP_SSL = [
  "unsupported or unrecognized ssl message",
  "wrong_version_number",
  "err_ssl_protocol_error",
  "plaintext http request was sent to https port",
];

/** True when TLS failed because the peer spoke plain HTTP (OpenSSL / Chromium wording). */
export function isPlainHttpSslFailure(error: unknown): boolean {
  const texts: string[] = [];
  let e: unknown = error;
  const seen = new Set<unknown>();
  while (e && !seen.has(e)) {
    seen.add(e);
    if (e instanceof Error) texts.push(e.message);
    else if (typeof e === "string") texts.push(e);
    e = e && typeof e === "object" && "cause" in e ? (e as { cause: unknown }).cause : undefined;
  }
  const joined = texts.join("\n").toLowerCase();
  return PLAIN_HTTP_SSL.some((s) => joined.includes(s));
}

/** Same host and port as [httpsUrl] but `http:`, or null when not HTTPS. */
export function plainHttpUrl(httpsUrl: string): string | null {
  let url: URL;
  try {
    url = new URL(httpsUrl);
  } catch {
    return null;
  }
  if (url.protocol !== "https:") return null;
  url.protocol = "http:";
  return url.href.replace(/\/+$/, "");
}

/** Same host and port as [httpUrl] but `https:`, or null when not HTTP. */
export function httpsUrl(httpUrl: string): string | null {
  let url: URL;
  try {
    url = new URL(httpUrl);
  } catch {
    return null;
  }
  if (url.protocol !== "http:") return null;
  url.protocol = "https:";
  return url.href.replace(/\/+$/, "");
}

/** First URL to probe for [session]: HTTPS when the saved URL is HTTPS or [preferHttps]. */
export function preferredProbeUrl(session: Pick<JellyfinSession, "serverUrl" | "preferHttps">): string {
  const url = session.serverUrl.trim().replace(/\/+$/, "");
  if (/^https:/i.test(url)) return url;
  if (session.preferHttps) return httpsUrl(url) ?? url;
  return url;
}

/**
 * 2.9.1 may have rewritten a saved `https:` entry to `http:`. That is ambiguous with intentional plain-HTTP
 * servers, so set [preferHttps] (keep the stored URL) so connects try HTTPS first.
 */
export function migratePreferHttps(session: JellyfinSession): JellyfinSession {
  const url = session.serverUrl.trim().replace(/\/+$/, "");
  if (!/^http:/i.test(url)) return session;
  return session.preferHttps ? session : jellyfinSession({ ...session, preferHttps: true });
}

export function bothSchemesFailed(httpsUrl: string, httpsErr: unknown, _httpUrl: string, httpErr: unknown): MediaSourceError {
  const host = (() => {
    try {
      const u = new URL(httpsUrl);
      return `${u.hostname}:${u.port || "443"}`;
    } catch {
      return httpsUrl;
    }
  })();
  const why = (err: unknown) => {
    const m = err instanceof Error ? err.message : String(err);
    return m.match(/\((.+)\)\s*$/)?.[1] ?? m;
  };
  return new MediaSourceError(`Cannot reach ${host} over HTTPS (${why(httpsErr)}) or HTTP (${why(httpErr)})`);
}
