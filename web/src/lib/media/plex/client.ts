import {
  buildUrl,
  currentPageProtocol,
  defaultFetch,
  delay,
  NotJsonError,
  parseJson,
  request,
  type ClientInfo,
  type FetchLike,
  type HttpOptions,
  type Query,
} from "../http";
import { MediaSourceError, messageOf } from "../mediaSource";
import type { PlexPin, PlexResource, PlexResourceConnection, PlexResponse, PlexUser } from "./dtos";

/**
 * A signed-in Plex server; persist this to skip linking next time. A plain object with PlexSession.kt's field names,
 * so the `px_servers` list reads the same as the apps'.
 */
export interface PlexSession {
  serverUrl: string;
  serverName: string;
  machineId: string;
  userName: string;
  /** Token for this server (differs from the account token on servers shared with the user). */
  serverToken: string;
  /** plex.tv account token, used to find the server again when its address changes. */
  accountToken: string;
}

/** A server on the user's Plex account, with every address plex.tv knows for it. */
export interface PlexServer {
  name: string;
  machineId: string;
  accessToken: string;
  owned: boolean;
  connections: PlexResourceConnection[];
}

export interface PlexLinked {
  accountToken: string;
  userName: string;
}

export interface PlexLinkOptions {
  /** Called once with the code to show: the user enters it at plex.tv/link. */
  onCode(code: string): void;
  /** Aborting it stops the polling; the returned promise rejects with the signal's reason. */
  signal?: AbortSignal;
  pollIntervalMs?: number;
}

/** Plex's options besides [HttpOptions]; [plexTvUrl] is only overridden by tests. */
export interface PlexClientOptions extends HttpOptions {
  plexTvUrl?: string;
  clock?: () => number;
}

/** An address that answered as the right server (null), or why it didn't. */
export interface ProbeResult {
  connection: PlexResourceConnection;
  error: string | null;
}

/** The message for a plain-http Plex address the browser won't reach from this https page. */
export const insecurePlexMessage = (url: URL): string =>
  `The browser won't connect to ${url.origin} over plain http from a page served over HTTPS`;

const TIMEOUT_MS = 30_000;
const PROBE_TIMEOUT_MS = 10_000;

/**
 * Minimal Plex client (PlexClient.kt): PIN linking and server discovery through plex.tv, then the server's own REST
 * API (JSON). From an https page only https addresses work, so servers are reached on the `*.plex.direct` https
 * addresses plex.tv lists (`includeHttps=1`), and plain-http ones are passed over without being tried.
 */
export class PlexClient {
  private readonly fetch: FetchLike;
  private readonly plexTvUrl: string;
  private readonly clock: () => number;
  private readonly pageProtocol: () => string;

  constructor(
    readonly clientInfo: ClientInfo,
    opts: PlexClientOptions = {},
  ) {
    this.fetch = opts.fetch ?? defaultFetch;
    this.plexTvUrl = opts.plexTvUrl ?? "https://plex.tv";
    this.clock = opts.clock ?? Date.now;
    this.pageProtocol = opts.pageProtocol !== undefined ? () => opts.pageProtocol ?? "" : currentPageProtocol;
  }

  /**
   * Creates a PIN, hands its code to [opts.onCode] and polls until the user enters it at plex.tv/link. Abort
   * [opts.signal] to cancel; throws when the code expires.
   */
  async link(opts: PlexLinkOptions): Promise<PlexLinked> {
    const { signal } = opts;
    const pin = parseJson<PlexPin>(await this.execute(this.plexTvUrl, null, "api/v2/pins", { post: true, query: [["strong", "false"]], signal }));
    opts.onCode(pin.code);
    const expiresAt = this.clock() + (pin.expiresIn ?? 900) * 1000;
    let token: string | null = null;
    while (token === null) {
      if (this.clock() > expiresAt) throw new MediaSourceError("The code expired; try again");
      await delay(opts.pollIntervalMs ?? 2000, signal);
      const status = parseJson<PlexPin>(await this.execute(this.plexTvUrl, null, `api/v2/pins/${pin.id}`, { signal }));
      token = status.authToken && status.authToken.trim() !== "" ? status.authToken : null;
    }
    const user = await this.account(token).catch(() => null);
    return { accountToken: token, userName: user?.username ?? user?.title ?? "" };
  }

  async account(accountToken: string): Promise<PlexUser> {
    return parseJson<PlexUser>(await this.execute(this.plexTvUrl, accountToken, "api/v2/user"));
  }

  /** Media servers on the account (owned and shared). */
  async servers(accountToken: string): Promise<PlexServer[]> {
    const resources = parseJson<PlexResource[]>(
      await this.execute(this.plexTvUrl, accountToken, "api/v2/resources", {
        query: [
          ["includeHttps", "1"],
          ["includeRelay", "1"],
        ],
      }),
    );
    return (Array.isArray(resources) ? resources : [])
      .filter((r) => (r.provides ?? "").split(",").some((p) => p.trim() === "server"))
      .map((r) => ({
        name: r.name ?? "",
        machineId: r.clientIdentifier ?? "",
        accessToken: r.accessToken ?? accountToken,
        owned: r.owned ?? false,
        connections: (r.connections ?? []).filter((c) => typeof c.uri === "string"),
      }));
  }

  /** Probes the server's addresses (all at once) and returns a session on the best reachable one: local, remote, then relay. */
  async connect(server: PlexServer, accountToken: string, userName: string): Promise<PlexSession> {
    const results = await this.probeAll(server);
    const best = results.find((r) => r.error === null)?.connection;
    if (!best) {
      const why = results.length > 0 ? results.map((r) => `\n${r.connection.uri}: ${r.error}`).join("") : "\nplex.tv lists no address for it";
      throw new MediaSourceError(`Can't reach ${server.name}; is it online?${why}`);
    }
    return {
      serverUrl: best.uri.replace(/\/+$/, ""),
      serverName: server.name,
      machineId: server.machineId,
      userName,
      serverToken: server.accessToken,
      accountToken,
    };
  }

  /** Every address of [server], best first (local, remote, relay), each with null when it answered or why it didn't. */
  probeAll(server: PlexServer): Promise<ProbeResult[]> {
    const rank = (c: PlexResourceConnection) => (c.relay ? 2 : c.local ? 0 : 1);
    const ordered = [...server.connections].sort((a, b) => rank(a) - rank(b));
    return Promise.all(ordered.map(async (connection) => ({ connection, error: await this.probe(connection.uri, server.accessToken, server.machineId) })));
  }

  private async probe(uri: string, token: string, machineId: string): Promise<string | null> {
    try {
      const identity = parseJson<PlexResponse>(await this.execute(uri, token, "identity", { timeoutMs: PROBE_TIMEOUT_MS }));
      const answered = identity?.MediaContainer?.machineIdentifier;
      return answered != null && answered !== machineId ? `a different server answered (${answered})` : null;
    } catch (e) {
      if (e instanceof NotJsonError) return `not a Plex server (${e.snippet})`;
      return messageOf(e);
    }
  }

  /**
   * Checks a stored session. Returns it unchanged when the server answers, a session on a new address when the old
   * one stopped working (or is plain http, which an https page can't use), null when the token was revoked. Throws
   * when the server can't be reached at all (keep the session; it may be offline).
   */
  async refresh(session: PlexSession): Promise<PlexSession | null> {
    try {
      await this.execute(session.serverUrl, session.serverToken, "identity", { timeoutMs: PROBE_TIMEOUT_MS });
      return session;
    } catch (e) {
      if (!(e instanceof MediaSourceError)) throw e;
      if (e.httpCode === 401) return null;
      if (e.httpCode !== null) throw e;
    }
    let server: PlexServer | undefined;
    try {
      server = (await this.servers(session.accountToken)).find((s) => s.machineId === session.machineId);
    } catch (e) {
      if (e instanceof MediaSourceError && e.httpCode === 401) return null;
      throw e;
    }
    if (!server) return null;
    return this.connect(server, session.accountToken, session.userName);
  }

  // --- HTTP plumbing ------------------------------------------------------------------

  /** [path] is already URL-encoded (Plex's keys, like "/library/parts/7/1700000000/file.mkv", come that way). */
  url(base: string, path: string, query: Query = []): URL {
    return buildUrl(base, path, query, true);
  }

  async get<T>(base: string, token: string, path: string, query: Query = []): Promise<T> {
    return parseJson<T>(await this.getRaw(base, token, path, {}, query));
  }

  getRaw(base: string, token: string | null, path: string, headers: Record<string, string> = {}, query: Query = []): Promise<string> {
    return this.execute(base, token, path, { headers, query });
  }

  private execute(
    base: string,
    token: string | null,
    path: string,
    opts: { post?: boolean; headers?: Record<string, string>; query?: Query; timeoutMs?: number; signal?: AbortSignal } = {},
  ): Promise<string> {
    const headers: Record<string, string> = {
      Accept: "application/json",
      "X-Plex-Product": this.clientInfo.clientName,
      "X-Plex-Version": this.clientInfo.version,
      "X-Plex-Client-Identifier": this.clientInfo.deviceId,
      "X-Plex-Device-Name": this.clientInfo.deviceName,
    };
    if (token !== null) headers["X-Plex-Token"] = token;
    Object.assign(headers, opts.headers);
    return request(this.fetch, this.url(base, path, opts.query), {
      method: opts.post ? "POST" : "GET",
      headers,
      timeoutMs: opts.timeoutMs ?? TIMEOUT_MS,
      signal: opts.signal,
      insecureMessage: insecurePlexMessage,
      pageProtocol: this.pageProtocol(),
    });
  }
}
