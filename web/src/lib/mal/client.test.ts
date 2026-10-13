import { beforeEach, describe, expect, it } from "vitest";
import { MAL_UNAVAILABLE, MalClient, MalError } from "./client";
import { fakeFetch, type FakeAnswer, type SeenRequest } from "./fakeFetch";
import { statusValue, type MalTokens } from "./models";

// Ported from MalClientTest.kt: the client against a fake YarmiplayServerTV passthrough to MyAnimeList.

const BASE = "https://home.example:8999";
const TOKEN = "/yarmiplay/mal/token";
const API = "/yarmiplay/mal/api";

let accessToken: string;
let refreshes: number;
let saved: (MalTokens | null)[];
let clock: number;
let server: ReturnType<typeof fakeFetch>;

function answer(r: SeenRequest): FakeAnswer {
  const path = r.url.pathname;
  if (path === TOKEN && r.body.includes("grant_type=authorization_code")) {
    return { body: { token_type: "Bearer", expires_in: 3600, access_token: "access-1", refresh_token: "refresh-1" } };
  }
  if (path === TOKEN && r.body.includes("grant_type=refresh_token")) {
    if (r.body.includes("refresh_token=revoked")) return { status: 400 };
    refreshes++;
    accessToken = `access-${refreshes + 1}`;
    return { body: { token_type: "Bearer", expires_in: 3600, access_token: accessToken, refresh_token: `refresh-${refreshes + 1}` } };
  }
  // Public data with the Client ID alone (no account), as MyAnimeList allows.
  if (
    r.headers.authorization == null &&
    r.headers["x-mal-client-id"] === "client-id" &&
    (path === `${API}/anime` || path === `${API}/anime/52991`) &&
    r.method === "GET"
  ) {
    return path === `${API}/anime`
      ? { body: { data: [{ node: { id: 52991, title: "Sousou no Frieren", num_episodes: 28 } }] } }
      : { body: { id: 52991, title: "Sousou no Frieren", num_episodes: 28 } };
  }
  if (r.headers.authorization !== `Bearer ${accessToken}`) return { status: 401 };
  if (path === `${API}/users/@me`) return { body: { id: 7, name: "ana", picture: "https://cdn.example/ana.jpg" } };
  if (path === `${API}/anime`) {
    return { body: { data: [{ node: { id: 52991, title: "Sousou no Frieren", num_episodes: 28, media_type: "tv", start_date: "2023-09-29" } }] } };
  }
  if (path === `${API}/anime/52991`) {
    return {
      body: {
        id: 52991,
        title: "Sousou no Frieren",
        num_episodes: 28,
        media_type: "tv",
        related_anime: [{ node: { id: 59978, title: "Sousou no Frieren 2nd Season" }, relation_type: "sequel" }],
        my_list_status: { status: "watching", score: 9, num_episodes_watched: 3, is_rewatching: false },
      },
    };
  }
  if (path === `${API}/anime/52991/my_list_status` && r.method === "PATCH") {
    return { body: { status: "watching", score: 9, num_episodes_watched: 4, is_rewatching: false, start_date: "2026-10-01" } };
  }
  if (path === `${API}/users/@me/animelist`) {
    return { body: { data: [{ node: { id: 52991, title: "Sousou no Frieren", num_episodes: 28 }, list_status: { status: "watching", num_episodes_watched: 3 } }] } };
  }
  return { status: 404 };
}

beforeEach(() => {
  accessToken = "access-1";
  refreshes = 0;
  saved = [];
  clock = 1_000_000;
  server = fakeFetch(answer);
});

function client(base: string | null = BASE) {
  const c = new MalClient("client-id", { passthroughBase: () => base, fetch: server.fetch, now: () => clock });
  c.onTokens = (t) => saved.push(t);
  return c;
}

async function failure(p: Promise<unknown>): Promise<MalError> {
  try {
    await p;
  } catch (e) {
    expect(e).toBeInstanceOf(MalError);
    return e as MalError;
  }
  throw new Error("expected it to fail");
}

describe("MalClient", () => {
  it("the sign-in page uses PKCE plain with the verifier as challenge", () => {
    const redirect = "https://tv.yarmiplay.com/watch/mal-callback.html";
    const url = new URL(client().authorizeUrl("st4te", "v".repeat(64), redirect));
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("client_id")).toBe("client-id");
    expect(url.searchParams.get("state")).toBe("st4te");
    expect(url.searchParams.get("code_challenge")).toBe("v".repeat(64));
    expect(url.searchParams.get("code_challenge_method")).toBe("plain");
    expect(url.searchParams.get("redirect_uri")).toBe(redirect);
    expect(url.origin + url.pathname).toBe("https://myanimelist.net/v1/oauth2/authorize");
    const verifier = MalClient.newVerifier();
    expect(verifier.length).toBeGreaterThanOrEqual(43);
    expect(verifier.length).toBeLessThanOrEqual(128);
    expect(verifier).toMatch(/^[A-Za-z0-9\-._~]+$/);
    expect(MalClient.newState()).toMatch(/^[A-Za-z0-9]{24}$/);
  });

  it("signing in exchanges the code through the passthrough, and calls send the bearer token", async () => {
    const c = client();
    const tokens = await c.exchange("the-code", "the-verifier", "https://tv.yarmiplay.com/watch/mal-callback.html");
    expect(tokens.accessToken).toBe("access-1");
    expect(tokens.expiresAtMillis).toBe(clock + 3_600_000);
    expect(saved).toEqual([tokens]);
    const token = server.requests.filter((r) => r.url.pathname === TOKEN);
    expect(token).toHaveLength(1);
    expect(token[0].method).toBe("POST");
    expect(token[0].url.origin).toBe(BASE);
    expect(token[0].headers["content-type"]).toBe("application/x-www-form-urlencoded");
    const body = token[0].body;
    expect(body).toContain("code=the-code");
    expect(body).toContain("code_verifier=the-verifier");
    expect(body).toContain("client_id=client-id");
    expect(body).not.toContain("client_secret");
    expect((await c.me()).name).toBe("ana");
  });

  it("an expired token is refreshed first, and a 401 refreshes once and retries", async () => {
    const c = client();
    await c.exchange("code", "verifier", "r");
    clock += 3_600_000;
    expect((await c.me()).name).toBe("ana");
    expect(refreshes).toBe(1);
    // The server revoked the access token early.
    accessToken = "access-other";
    await c.me().catch(() => undefined);
    expect(refreshes).toBe(2);
    expect(saved[saved.length - 1]!.refreshToken).toBe("refresh-3");
  });

  it("requests at the same time share one refresh", async () => {
    const c = client();
    await c.exchange("code", "verifier", "r");
    clock += 3_600_000;
    await Promise.all([c.me(), c.me(), c.anime(52991)]);
    expect(refreshes).toBe(1);
  });

  it("a refresh token that stopped working signs out", async () => {
    const c = client();
    c.tokens = { accessToken: "old", refreshToken: "revoked", expiresAtMillis: clock - 1 };
    const e = await failure(c.me());
    expect(e.httpCode).toBe(401);
    expect(c.tokens).toBeNull();
    expect(saved).toEqual([null]);
  });

  it("search, details, the list, and updates as form fields", async () => {
    const c = client();
    await c.exchange("code", "verifier", "r");
    expect(await c.search("ab")).toEqual([]);
    expect((await c.search("Frieren")).map((a) => a.id)).toEqual([52991]);
    const anime = await c.anime(52991);
    expect(anime.relatedAnime.map((r) => [r.node.id, r.relationType])).toEqual([[59978, "sequel"]]);
    expect(anime.myListStatus!.watchedEpisodes).toBe(3);
    expect(statusValue(anime.myListStatus)).toBe("watching");
    expect((await c.myList("watching")).map(([a]) => a.id)).toEqual([52991]);

    const now = await c.updateStatus(52991, { status: "watching", watchedEpisodes: 4, startDate: "2026-10-01" });
    expect(now.watchedEpisodes).toBe(4);
    const patch = server.requests.filter((r) => r.method === "PATCH").pop()!;
    expect(patch.body).toBe("status=watching&num_watched_episodes=4&start_date=2026-10-01");
    expect(patch.url.pathname).toBe(`${API}/anime/52991/my_list_status`);
  });

  it("a search with list status asks for your entry only when signed in", async () => {
    const c = client();
    const lastFields = () => server.requests.filter((r) => r.url.pathname === `${API}/anime`).pop()!.url.searchParams.get("fields")!;
    await c.search("Frieren", 10, true);
    expect(lastFields()).not.toContain("my_list_status"); // signed out: public data only
    await c.exchange("code", "verifier", "r");
    await c.search("Frieren");
    expect(lastFields()).not.toContain("my_list_status"); // not asked for
    await c.search("Frieren", 10, true);
    expect(lastFields()).toContain("my_list_status");
  });

  it("signed out, search and details use the Client ID alone, and the list still needs an account", async () => {
    const c = client();
    expect((await c.search("Frieren")).map((a) => a.id)).toEqual([52991]);
    const anime = await c.anime(52991);
    expect(anime.numEpisodes).toBe(28);
    expect(anime.myListStatus).toBeNull();
    const pub = server.requests.filter((r) => r.url.pathname.startsWith(`${API}/anime`));
    expect(pub.every((r) => r.headers["x-mal-client-id"] === "client-id" && r.headers.authorization == null)).toBe(true);
    expect((await failure(c.myList("watching"))).httpCode).toBe(401);

    await c.exchange("code", "verifier", "r");
    await c.anime(52991);
    expect(server.requests[server.requests.length - 1].headers.authorization).toBe("Bearer access-1"); // signed in, as the user
  });

  it("without a Client ID nothing is asked signed out", async () => {
    const c = new MalClient("", { passthroughBase: () => BASE, fetch: server.fetch });
    expect((await failure(c.search("Frieren"))).httpCode).toBe(401);
    expect(server.requests).toEqual([]);
  });

  it("long searches are cut at a word boundary, never mid-word", async () => {
    const long = "As a Reincarnated Aristocrat, I'll Use My Appraisal Skill to Rise in the World";
    expect(MalClient.searchQuery(long)).toBe("As a Reincarnated Aristocrat, I'll Use My Appraisal Skill to");
    expect(MalClient.searchQuery(long).length).toBeLessThanOrEqual(64);
    expect(MalClient.searchQuery("  Frieren  ")).toBe("Frieren");
    const c = client();
    await c.exchange("code", "verifier", "r");
    await c.search(long);
    const q = server.requests.filter((r) => r.url.pathname === `${API}/anime`).pop()!.url.searchParams.get("q");
    expect(q).toBe("As a Reincarnated Aristocrat, I'll Use My Appraisal Skill to");
  });

  // --- The browser's own: the passthrough --------------------------------------------------------------------------

  it("without a YarmiplayServerTV to pass through, MyAnimeList is unavailable and nothing is sent", async () => {
    const c = client(null);
    expect(c.available).toBe(false);
    const e = await failure(c.search("Frieren"));
    expect(e.message).toBe(MAL_UNAVAILABLE);
    expect(e.httpCode).toBeNull();
    expect(server.requests).toEqual([]);
  });

  it("the passthrough's rate limit is an error with its message", async () => {
    const limited = fakeFetch(() => ({ status: 429, body: "Too many MyAnimeList requests; try again in a minute" }));
    const c = new MalClient("client-id", { passthroughBase: () => `${BASE}/`, fetch: limited.fetch });
    const e = await failure(c.anime(1));
    expect(e.httpCode).toBe(429);
    expect(e.message).toBe("Too many MyAnimeList requests; try again in a minute");
    expect(limited.requests[0].url.href).toBe(`${BASE}${API}/anime/1?fields=${encodeURIComponent("id,title,alternative_titles,main_picture,start_date,media_type,num_episodes,status,mean,related_anime,my_list_status")}`);
  });

  it("a request that can't be sent says MyAnimeList can't be reached", async () => {
    const down = fakeFetch(() => {
      throw new TypeError("Failed to fetch");
    });
    const c = new MalClient("client-id", { passthroughBase: () => BASE, fetch: down.fetch });
    const e = await failure(c.anime(1));
    expect(e.message).toBe("Cannot reach MyAnimeList (Failed to fetch)");
    expect(e.httpCode).toBeNull();
  });
});
