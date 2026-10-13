import {
  emptyListStatus,
  formFields,
  parseAnime,
  parseListStatus,
  parsePage,
  parseTokenResponse,
  parseUser,
  type MalAnime,
  type MalListStatus,
  type MalListUpdate,
  type MalStatus,
  type MalTokens,
  type MalUser,
} from "./models";

/** `fetch` as the client uses it; tests pass their own. */
export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>;

/** A MyAnimeList request that failed: [httpCode] is the answer's status, or null when there was no answer. */
export class MalError extends Error {
  constructor(
    message: string,
    readonly httpCode: number | null = null,
  ) {
    super(message);
    this.name = "MalError";
  }
}

/** Why MyAnimeList can't be asked from this page: there's no YarmiplayServerTV to pass the requests through. */
export const MAL_UNAVAILABLE = "MyAnimeList needs a YarmiplayServerTV 1.8 room or a saved one";

export interface MalClientOptions {
  /**
   * The YarmiplayServerTV (1.8 or later) that passes the requests on to MyAnimeList, e.g.
   * `https://myhome.duckdns.org:8999`; null when there's none, and MyAnimeList can't be asked. MyAnimeList refuses
   * browsers' cross-origin requests, so the browser never asks it directly.
   */
  passthroughBase: () => string | null;
  fetch?: FetchLike;
  /** Milliseconds, as `Date.now()`. */
  now?: () => number;
  /** Where the sign-in page is (a page the browser opens, not a request). */
  authUrl?: string;
}

/** A random source: [n] values below [bound]. */
export type RandomInts = (n: number, bound: number) => number[];

const secureRandom: RandomInts = (n, bound) => {
  // Rejection sampling, so every character is as likely.
  const out: number[] = [];
  const limit = 256 - (256 % bound);
  while (out.length < n) {
    for (const b of crypto.getRandomValues(new Uint8Array(n * 2))) {
      if (b < limit && out.length < n) out.push(b % bound);
    }
  }
  return out;
};

const LIST_FIELDS = "id,title,alternative_titles,main_picture,start_date,media_type,num_episodes,status";
const DETAIL_FIELDS = `${LIST_FIELDS},mean,related_anime,my_list_status`;
const VERIFIER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
const MAX_QUERY = 64;

/**
 * MyAnimeList API v2 client. Signs in with OAuth 2 and PKCE ("plain", the only method MyAnimeList supports, with no
 * client secret), refreshes the access token when it runs out, and reads and updates the user's anime list.
 * Only the API is used, never the website. [clientId] is the app's registered id: it's never logged or shown.
 *
 * In the browser every request goes through YarmiplayServerTV's passthrough on its Syncplay port: the token at
 * `{base}/yarmiplay/mal/token`, the API at `{base}/yarmiplay/mal/api/{path}`. It passes on only `Authorization`,
 * `X-MAL-CLIENT-ID` and `Content-Type`, and limits how often an address may ask (a 429 with a message, which is
 * this client's error).
 */
export class MalClient {
  /** The signed-in user's tokens; null when signed out. */
  tokens: MalTokens | null = null;
  /** Told about new tokens (after signing in or a refresh) and null when the refresh token stopped working. */
  onTokens: (tokens: MalTokens | null) => void = () => {};

  private readonly passthroughBase: () => string | null;
  private readonly fetch: FetchLike;
  private readonly now: () => number;
  private readonly authUrl: string;
  /** The refresh under way, which every request that needs one waits for. */
  private refreshing: Promise<MalTokens> | null = null;

  constructor(
    private readonly clientId: string,
    options: MalClientOptions,
  ) {
    this.passthroughBase = options.passthroughBase;
    this.fetch = options.fetch ?? ((url, init) => globalThis.fetch(url, init));
    this.now = options.now ?? Date.now;
    this.authUrl = (options.authUrl ?? "https://myanimelist.net").replace(/\/+$/, "");
  }

  /** Whether this build has a Client ID (it comes from the build, never from the repository). */
  get configured(): boolean {
    return this.clientId.trim() !== "";
  }

  /** Whether there's a YarmiplayServerTV to ask MyAnimeList through now. */
  get available(): boolean {
    return this.passthroughBase() != null;
  }

  /** The page where the user signs in on MyAnimeList itself; it comes back to [redirectUri] with a code. */
  authorizeUrl(state: string, verifier: string, redirectUri: string): string {
    const q = new URLSearchParams({
      response_type: "code",
      client_id: this.clientId,
      state,
      redirect_uri: redirectUri,
      code_challenge: verifier,
      code_challenge_method: "plain",
    });
    return `${this.authUrl}/v1/oauth2/authorize?${q}`;
  }

  /** Trades the code from the sign-in page for tokens, and keeps them. */
  async exchange(code: string, verifier: string, redirectUri: string): Promise<MalTokens> {
    const t = await this.token([
      ["grant_type", "authorization_code"],
      ["code", code],
      ["code_verifier", verifier],
      ["redirect_uri", redirectUri],
    ]);
    this.keep(t);
    return t;
  }

  private async refresh(): Promise<MalTokens> {
    const current = this.tokens;
    if (!current) throw signedOut();
    try {
      const t = await this.token([
        ["grant_type", "refresh_token"],
        ["refresh_token", current.refreshToken],
      ]);
      this.keep(t);
      return t;
    } catch (e) {
      if (e instanceof MalError && (e.httpCode === 400 || e.httpCode === 401)) {
        this.tokens = null;
        this.onTokens(null);
        throw signedOut();
      }
      throw e;
    }
  }

  private keep(t: MalTokens) {
    this.tokens = t;
    this.onTokens(t);
  }

  private async token(fields: [string, string][]): Promise<MalTokens> {
    const body = new URLSearchParams([["client_id", this.clientId], ...fields]).toString();
    const text = await this.execute(`${this.base()}/yarmiplay/mal/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    const r = parseTokenResponse(readJson(text));
    return { accessToken: r.accessToken, refreshToken: r.refreshToken, expiresAtMillis: this.now() + r.expiresIn * 1000 };
  }

  async me(): Promise<MalUser> {
    return parseUser(readJson(await this.call(this.api("users/@me", { fields: "picture" }))));
  }

  /**
   * Up to [limit] anime whose titles match [query] (MyAnimeList needs at least 3 characters). Works signed out
   * too, as public data; signed in, [withListStatus] also brings your list entry for each.
   */
  async search(query: string, limit = 10, withListStatus = false): Promise<MalAnime[]> {
    const q = MalClient.searchQuery(query);
    if (q.length < 3) return [];
    const fields = withListStatus && this.tokens != null ? `${LIST_FIELDS},my_list_status` : LIST_FIELDS;
    const url = this.api("anime", { q, limit: String(Math.min(Math.max(limit, 1), 100)), fields });
    return parsePage(readJson(await this.publicCall(url))).map((d) => d.node);
  }

  /** Anime [id]'s details; signed in, with your list entry. Works signed out too, as public data. */
  async anime(id: number): Promise<MalAnime> {
    return parseAnime(readJson(await this.publicCall(this.api(`anime/${id}`, { fields: DETAIL_FIELDS }))));
  }

  /** Changes your list entry for anime [id]; returns the entry as it is now. */
  async updateStatus(id: number, update: MalListUpdate): Promise<MalListStatus> {
    const body = new URLSearchParams(formFields(update)).toString();
    const text = await this.call(this.api(`anime/${id}/my_list_status`), {
      method: "PATCH",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    return parseListStatus(readJson(text)) ?? emptyListStatus();
  }

  async deleteStatus(id: number): Promise<void> {
    await this.call(this.api(`anime/${id}/my_list_status`), { method: "DELETE" });
  }

  /** Your list entries with [status] (e.g. what you're watching now), most recently updated first. */
  async myList(status: MalStatus, limit = 100): Promise<[MalAnime, MalListStatus][]> {
    const url = this.api("users/@me/animelist", {
      status,
      sort: "list_updated_at",
      limit: String(Math.min(Math.max(limit, 1), 1000)),
      fields: `list_status,${LIST_FIELDS}`,
    });
    return parsePage(readJson(await this.call(url))).map((d) => [d.node, d.listStatus ?? emptyListStatus()]);
  }

  private base(): string {
    const base = this.passthroughBase();
    if (!base) throw new MalError(MAL_UNAVAILABLE);
    return base.replace(/\/+$/, "");
  }

  /** The URL that asks MyAnimeList's `{path}` through the passthrough (read when the request is made). */
  private api(path: string, query: Record<string, string> = {}): () => string {
    return () => {
      const q = new URLSearchParams(query).toString();
      return `${this.base()}/yarmiplay/mal/api/${path}${q ? `?${q}` : ""}`;
    };
  }

  /**
   * Public data (titles, episodes, related anime): as the signed-in user when there is one, else with the app's
   * Client ID alone, which MyAnimeList allows for public data.
   */
  private async publicCall(url: () => string): Promise<string> {
    if (this.tokens != null) return this.call(url);
    if (!this.configured) throw signedOut();
    return this.execute(url(), { headers: { "X-MAL-CLIENT-ID": this.clientId } });
  }

  /** Runs an API request as the signed-in user, refreshing the access token first when it ran out, or once on a 401. */
  private async call(url: () => string, init: RequestInit = {}): Promise<string> {
    let t = this.tokens;
    if (!t) throw signedOut();
    if (t.expiresAtMillis - this.now() < 60_000) t = await this.refreshed((c) => c.expiresAtMillis - this.now() >= 60_000);
    const withToken = (token: MalTokens): RequestInit => ({
      ...init,
      headers: { ...(init.headers as Record<string, string> | undefined), Authorization: `Bearer ${token.accessToken}` },
    });
    try {
      return await this.execute(url(), withToken(t));
    } catch (e) {
      if (!(e instanceof MalError) || e.httpCode !== 401) throw e;
      const used = t.accessToken;
      const fresh = await this.refreshed((c) => c.accessToken !== used);
      return this.execute(url(), withToken(fresh));
    }
  }

  /** The tokens, refreshed unless another request's refresh already gave ones that are [good]. One refresh at a time. */
  private async refreshed(good: (t: MalTokens) => boolean): Promise<MalTokens> {
    while (this.refreshing) {
      try {
        await this.refreshing;
      } catch {
        // That refresh's own request reports it; this one looks again.
      }
    }
    const current = this.tokens;
    if (current && good(current)) return current;
    const p = this.refresh();
    this.refreshing = p;
    try {
      return await p;
    } finally {
      if (this.refreshing === p) this.refreshing = null;
    }
  }

  private async execute(url: string, init: RequestInit = {}): Promise<string> {
    let response: Response;
    try {
      response = await this.fetch(url, init);
    } catch (e) {
      throw new MalError(`Cannot reach MyAnimeList (${e instanceof Error ? e.message : String(e)})`);
    }
    const text = await response.text().catch(() => "");
    if (!response.ok) {
      const code = response.status;
      let message: string;
      switch (code) {
        case 401:
          message = "Not signed in to MyAnimeList (401)";
          break;
        case 403:
          message = "MyAnimeList refused the request (403)";
          break;
        case 404:
          message = "Not found on MyAnimeList (404)";
          break;
        case 429:
          // YarmiplayServerTV's limit says so in plain text; MyAnimeList's own doesn't.
          message = plainText(text) ?? "MyAnimeList is busy; trying again later (429)";
          break;
        default:
          message = `MyAnimeList answered ${code}`;
      }
      throw new MalError(message, code);
    }
    return text;
  }

  /** [query] as MyAnimeList takes it: at most 64 characters, cut at a word boundary rather than mid-word. */
  static searchQuery(query: string): string {
    const q = query.trim().replace(/\s+/g, " ");
    if (q.length <= MAX_QUERY) return q;
    const space = q.slice(0, MAX_QUERY + 1).lastIndexOf(" ");
    const cut = space >= 3 ? space : MAX_QUERY;
    return q.slice(0, cut).trim().replace(/[,:\- ]+$/, "");
  }

  /** A PKCE code verifier (also the challenge, with the "plain" method): 64 unreserved characters. */
  static newVerifier(random: RandomInts = secureRandom): string {
    return random(64, VERIFIER_CHARS.length)
      .map((i) => VERIFIER_CHARS[i])
      .join("");
  }

  /** A random `state` that ties the sign-in page's answer to this sign-in. */
  static newState(random: RandomInts = secureRandom): string {
    return random(24, 62)
      .map((i) => VERIFIER_CHARS[i])
      .join("");
  }
}

const signedOut = () => new MalError("Not signed in to MyAnimeList", 401);

function readJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    throw new MalError("MyAnimeList sent something unreadable");
  }
}

/** A short plain-text message (not JSON or a page), to show as it is. */
function plainText(text: string): string | null {
  const t = text.trim();
  if (!t || t.length > 300 || t.startsWith("{") || t.startsWith("[") || t.startsWith("<")) return null;
  return t;
}
