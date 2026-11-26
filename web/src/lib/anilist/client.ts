/**
 * AniList GraphQL v2 client & local service for the Web app.
 * AniList's GraphQL endpoint natively supports browser CORS, so the web app talks to AniList directly
 * (never sends your token or list through any Syncplay server) using Implicit Grant OAuth.
 */

export type AniListStatus = "CURRENT" | "COMPLETED" | "PAUSED" | "DROPPED" | "PLANNING" | "REPEATING";

export const ANILIST_STATUSES: readonly { id: AniListStatus; label: string }[] = [
  { id: "CURRENT", label: "Watching" },
  { id: "PLANNING", label: "Plan to watch" },
  { id: "COMPLETED", label: "Completed" },
  { id: "PAUSED", label: "Paused" },
  { id: "DROPPED", label: "Dropped" },
  { id: "REPEATING", label: "Rewatching" },
];

export interface AniListEntryStatus {
  id: number;
  status: AniListStatus | null;
  score: number;
  progress: number;
  repeat: number;
}

export interface AniListAnime {
  id: number;
  idMal: number | null;
  title: string;
  englishTitle: string | null;
  coverUrl: string | null;
  seasonYear: number | null;
  format: string | null;
  episodes: number;
  averageScore: number | null;
  listEntry: AniListEntryStatus | null;
}

export interface AniListAccount {
  accessToken: string;
  expiresAt: number;
  userId: number;
  userName: string;
  avatarUrl: string | null;
  scoreFormat: string;
}

export interface AniListSettings {
  account: AniListAccount | null;
  autoUpdate: boolean;
  askFirst: boolean;
  ignored: string[];
}

const STORAGE_KEY = "ytv:anilist";
const API_URL = "https://graphql.anilist.co";

export function loadAniListSettings(): AniListSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { account: null, autoUpdate: true, askFirst: false, ignored: [] };
    const parsed = JSON.parse(raw) as Partial<AniListSettings>;
    return {
      account: parsed.account ?? null,
      autoUpdate: parsed.autoUpdate ?? true,
      askFirst: parsed.askFirst ?? false,
      ignored: Array.isArray(parsed.ignored) ? parsed.ignored : [],
    };
  } catch {
    return { account: null, autoUpdate: true, askFirst: false, ignored: [] };
  }
}

export function saveAniListSettings(s: AniListSettings) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
  } catch {
    // Private mode; kept in memory only.
  }
}

export function clearAniListStorage() {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Ignore.
  }
}

export class WebAniListClient {
  constructor(
    private readonly clientId: string,
    private readonly getToken: () => string | null,
    private readonly onExpired: () => void = () => {},
  ) {}

  get configured(): boolean {
    return this.clientId.trim().length > 0;
  }

  authorizeUrl(redirectUri?: string): string {
    const u = new URL("https://anilist.co/api/v2/oauth/authorize");
    u.searchParams.set("client_id", this.clientId);
    u.searchParams.set("response_type", "token");
    if (redirectUri) u.searchParams.set("redirect_uri", redirectUri);
    return u.toString();
  }

  parseHash(hash: string): { accessToken: string; expiresAt: number } | null {
    const clean = hash.replace(/^[#?]+/, "");
    if (!clean.includes("access_token=")) return null;
    const params = new URLSearchParams(clean);
    const token = params.get("access_token");
    if (!token) return null;
    const expSec = Number(params.get("expires_in") ?? "31536000") || 31536000;
    return { accessToken: token, expiresAt: Date.now() + expSec * 1000 };
  }

  async viewer(tokenOverride?: string): Promise<AniListAccount> {
    const tok = tokenOverride ?? this.getToken();
    if (!tok) throw new Error("Not signed in to AniList");
    const data = await this.graphql<{
      Viewer: { id: number; name: string; avatar?: { medium?: string }; mediaListOptions?: { scoreFormat?: string } };
    }>("query { Viewer { id name avatar { medium } mediaListOptions { scoreFormat } } }", {}, tok);
    return {
      accessToken: tok,
      expiresAt: Date.now() + 31536000 * 1000,
      userId: data.Viewer.id,
      userName: data.Viewer.name,
      avatarUrl: data.Viewer.avatar?.medium ?? null,
      scoreFormat: data.Viewer.mediaListOptions?.scoreFormat ?? "POINT_10",
    };
  }

  async search(query: string, perPage = 20): Promise<AniListAnime[]> {
    const q = query.trim();
    if (q.length < 2) return [];
    const data = await this.graphql<{ Page: { media: RawMedia[] } }>(
      `query ($search: String, $perPage: Int) {
        Page(page: 1, perPage: $perPage) {
          media(search: $search, type: ANIME) {
            id idMal format episodes seasonYear averageScore
            title { userPreferred romaji english }
            coverImage { medium }
            mediaListEntry { id status score progress repeat }
          }
        }
      }`,
      { search: q, perPage },
    );
    return (data.Page?.media ?? []).map(toAnime);
  }

  async myList(userId: number, status: AniListStatus): Promise<AniListAnime[]> {
    const data = await this.graphql<{
      MediaListCollection: { lists: { entries: (RawEntry & { media: RawMedia })[] }[] };
    }>(
      `query ($userId: Int, $status: MediaListStatus) {
        MediaListCollection(userId: $userId, type: ANIME, status: $status, sort: UPDATED_TIME_DESC) {
          lists {
            entries {
              id status score progress repeat
              media {
                id idMal format episodes seasonYear averageScore
                title { userPreferred romaji english }
                coverImage { medium }
              }
            }
          }
        }
      }`,
      { userId, status },
    );
    const out: AniListAnime[] = [];
    for (const list of data.MediaListCollection?.lists ?? []) {
      for (const e of list.entries ?? []) {
        out.push({
          ...toAnime(e.media),
          listEntry: { id: e.id, status: e.status ?? null, score: e.score ?? 0, progress: e.progress ?? 0, repeat: e.repeat ?? 0 },
        });
      }
    }
    return out;
  }

  async saveEntry(
    mediaId: number,
    update: { status?: AniListStatus; progress?: number; score?: number; repeat?: number },
  ): Promise<AniListEntryStatus> {
    const data = await this.graphql<{ SaveMediaListEntry: RawEntry }>(
      `mutation ($mediaId: Int, $status: MediaListStatus, $progress: Int, $score: Float, $repeat: Int) {
        SaveMediaListEntry(mediaId: $mediaId, status: $status, progress: $progress, score: $score, repeat: $repeat) {
          id status score progress repeat
        }
      }`,
      { mediaId, ...update },
    );
    const e = data.SaveMediaListEntry;
    return { id: e.id, status: e.status ?? null, score: e.score ?? 0, progress: e.progress ?? 0, repeat: e.repeat ?? 0 };
  }

  async deleteEntry(entryId: number): Promise<void> {
    await this.graphql("mutation ($id: Int) { DeleteMediaListEntry(id: $id) { deleted } }", { id: entryId });
  }

  private async graphql<T>(query: string, variables: Record<string, unknown>, tokenOverride?: string): Promise<T> {
    const headers: Record<string, string> = { "Content-Type": "application/json", Accept: "application/json" };
    const tok = tokenOverride ?? this.getToken();
    if (tok) headers.Authorization = `Bearer ${tok}`;
    const res = await fetch(API_URL, { method: "POST", headers, body: JSON.stringify({ query, variables }) });
    if (res.status === 401) {
      if (tok) this.onExpired();
      throw new Error("Not signed in to AniList");
    }
    if (!res.ok) throw new Error(`AniList answered ${res.status}`);
    const body = (await res.json()) as { data?: T; errors?: { message?: string }[] };
    if (body.errors?.length) throw new Error(body.errors[0]?.message || "AniList error");
    if (!body.data) throw new Error("Empty AniList response");
    return body.data;
  }
}

interface RawEntry {
  id: number;
  status?: AniListStatus | null;
  score?: number;
  progress?: number;
  repeat?: number;
}

interface RawMedia {
  id: number;
  idMal?: number | null;
  format?: string | null;
  episodes?: number | null;
  seasonYear?: number | null;
  averageScore?: number | null;
  title?: { userPreferred?: string; romaji?: string; english?: string };
  coverImage?: { medium?: string };
  mediaListEntry?: RawEntry | null;
}

function toAnime(m: RawMedia): AniListAnime {
  const title = m.title?.userPreferred || m.title?.romaji || m.title?.english || `Anime #${m.id}`;
  return {
    id: m.id,
    idMal: m.idMal ?? null,
    title,
    englishTitle: m.title?.english && m.title.english !== title ? m.title.english : null,
    coverUrl: m.coverImage?.medium ?? null,
    seasonYear: m.seasonYear ?? null,
    format: m.format ?? null,
    episodes: m.episodes ?? 0,
    averageScore: m.averageScore ?? null,
    listEntry: m.mediaListEntry
      ? {
          id: m.mediaListEntry.id,
          status: m.mediaListEntry.status ?? null,
          score: m.mediaListEntry.score ?? 0,
          progress: m.mediaListEntry.progress ?? 0,
          repeat: m.mediaListEntry.repeat ?? 0,
        }
      : null,
  };
}
