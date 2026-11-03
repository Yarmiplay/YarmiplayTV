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
}

/** A [JellyfinSession] with the defaults for its sharing fields. */
export const jellyfinSession = (
  s: Pick<JellyfinSession, "serverUrl" | "serverName" | "serverId" | "userId" | "userName" | "accessToken"> & Partial<JellyfinSession>,
): JellyfinSession => ({ sharedBy: "", candidateUrls: [], noLongerShared: false, ...s });

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
   * Starts Quick Connect, hands the code to [opts.onCode] and polls until a user approves it in another Jellyfin
   * client (Settings → Quick Connect). Abort [opts.signal] to cancel.
   */
  /**
   * [normalizeServerUrl], then if that is HTTPS and the peer answers with a plain-HTTP TLS handshake error
   * (e.g. "Unsupported or unrecognized SSL message"), the same host and port over `http://`.
   */
  async resolveReachableUrl(input: string): Promise<string> {
    const httpsOrHttp = this.normalizeServerUrl(input);
    if (!/^https:/i.test(httpsOrHttp)) return httpsOrHttp;
    try {
      await this.publicInfo(httpsOrHttp);
      return httpsOrHttp;
    } catch (httpsErr) {
      if (!isPlainHttpSslFailure(httpsErr)) throw httpsErr;
      const http = plainHttpUrl(httpsOrHttp);
      if (!http) throw httpsErr;
      try {
        await this.publicInfo(http);
        return http;
      } catch (httpErr) {
        throw bothSchemesFailed(httpsOrHttp, httpsErr, http, httpErr);
      }
    }
  }

  /**
   * When [error] shows [session]'s HTTPS URL is plain HTTP on that port, a copy on `http://` that still
   * validates; otherwise null.
   */
  async sessionOnPlainHttp(session: JellyfinSession, error: unknown): Promise<JellyfinSession | null> {
    if (!isPlainHttpSslFailure(error)) return null;
    const http = plainHttpUrl(session.serverUrl);
    if (!http) return null;
    const moved = jellyfinSession({ ...session, serverUrl: http });
    try {
      return (await this.validate(moved)) ? moved : null;
    } catch {
      return null;
    }
  }

  async quickConnect(serverUrl: string, opts: QuickConnectOptions): Promise<JellyfinSession> {
    const { signal } = opts;
    const base = await this.resolveReachableUrl(serverUrl);
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
    return sessionFrom(base, info, auth);
  }

  async login(serverUrl: string, username: string, password: string): Promise<JellyfinSession> {
    const base = await this.resolveReachableUrl(serverUrl);
    const info = await this.publicInfo(base);
    const body: AuthenticateByNameBody = { Username: username, Pw: password };
    let auth: AuthenticationResult;
    try {
      auth = await this.post(base, null, "Users/AuthenticateByName", JSON.stringify(body));
    } catch (e) {
      if (e instanceof MediaSourceError && e.httpCode === 401) throw new MediaSourceError("Wrong username or password", 401);
      throw e;
    }
    return sessionFrom(base, info, auth);
  }

  /** Checks a stored session is still valid; throws when the server can't be reached (keep the session then). */
  async validate(session: JellyfinSession): Promise<boolean> {
    try {
      await this.getRaw(session.serverUrl, session.accessToken, "Users/Me");
      return true;
    } catch (e) {
      if (e instanceof MediaSourceError && (e.httpCode === 401 || e.httpCode === 403)) return false;
      throw e;
    }
  }

  async logout(session: JellyfinSession): Promise<void> {
    await this.postRaw(session.serverUrl, session.accessToken, "Sessions/Logout", null).catch(() => {});
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
