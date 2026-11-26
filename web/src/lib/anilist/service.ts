import { Store } from "../store";
import type { EpisodeInfo } from "../mal/episode";
import type { MalNotice } from "../mal/service";
import {
  clearAniListStorage,
  loadAniListSettings,
  saveAniListSettings,
  WebAniListClient,
  type AniListAccount,
  type AniListAnime,
  type AniListSettings,
  type AniListStatus,
} from "./client";

export type AniListState =
  | { kind: "notConfigured" }
  | { kind: "signedOut" }
  | { kind: "idle" }
  | { kind: "ignored"; info: EpisodeInfo }
  | { kind: "matching"; info: EpisodeInfo }
  | { kind: "matched"; info: EpisodeInfo; episode: number; entry: AniListAnime; candidates: AniListAnime[] }
  | { kind: "unsure"; info: EpisodeInfo; candidates: AniListAnime[] }
  | { kind: "failed"; info: EpisodeInfo; message: string };

const MAPPINGS_KEY = "ytv:anilist_mappings";
const WATCHED_FRACTION = 0.9;

interface Mapping {
  mediaId: number;
  offset: number;
}

function loadMappings(): Record<string, Mapping> {
  try {
    return JSON.parse(localStorage.getItem(MAPPINGS_KEY) || "{}") as Record<string, Mapping>;
  } catch {
    return {};
  }
}

function saveMappings(map: Record<string, Mapping>) {
  try {
    localStorage.setItem(MAPPINGS_KEY, JSON.stringify(map));
  } catch {
    // Ignore storage errors in private mode.
  }
}

function mappingKey(info: EpisodeInfo): string {
  return info.season != null ? `${info.seriesKey}#s${info.season}` : info.seriesKey;
}

function norm(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function makeNotice(
  text: string,
  opts: { undo?: (() => void) | null; pick?: boolean; confirm?: (() => void) | null } = {},
): MalNotice {
  return {
    text,
    undo: opts.undo ?? null,
    pick: opts.pick ?? false,
    confirm: opts.confirm ?? null,
    at: Date.now(),
  };
}

export class WebAniListService {
  readonly settingsStore = new Store<AniListSettings>(loadAniListSettings());
  readonly state: Store<AniListState>;
  readonly notice = new Store<MalNotice | null>(null);
  readonly canUndo = new Store<boolean>(false);
  readonly client: WebAniListClient;

  private mappings: Record<string, Mapping> = loadMappings();
  private playingInfo: EpisodeInfo | null = null;
  private lastUndo: (() => void) | null = null;
  private reportedKey: string | null = null;

  constructor(clientId: string) {
    this.client = new WebAniListClient(
      clientId,
      () => this.settingsStore.value.account?.accessToken ?? null,
      () => this.signOut(true),
    );
    this.state = new Store<AniListState>(this.client.configured ? { kind: "idle" } : { kind: "notConfigured" });
  }

  get signedIn(): boolean {
    return this.settingsStore.value.account !== null;
  }

  /**
   * Captures `#access_token=...` returned by AniList Implicit Grant on page load, immediately strips
   * the token from `window.location.hash` via `history.replaceState` so it never lingers in history
   * or referrers, and completes sign-in.
   */
  async captureHashRedirect(): Promise<boolean> {
    if (typeof window === "undefined") return false;
    const hash = window.location.hash;
    if (!hash || !hash.includes("access_token=")) return false;
    const parsed = this.client.parseHash(hash);
    try {
      window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}#/anilist`);
    } catch {
      // Ignore history errors.
    }
    if (!parsed) return false;
    await this.signInWithToken(parsed.accessToken, parsed.expiresAt);
    return true;
  }

  beginSignIn(): string {
    const redirect = `${window.location.origin}${window.location.pathname}`;
    return this.client.authorizeUrl(redirect);
  }

  async signInWithInput(input: string): Promise<AniListAccount> {
    const trimmed = input.trim();
    const parsed = this.client.parseHash(trimmed);
    if (parsed) return this.signInWithToken(parsed.accessToken, parsed.expiresAt);
    if (trimmed.length >= 20 && !trimmed.includes(" ")) {
      return this.signInWithToken(trimmed, Date.now() + 31536000 * 1000);
    }
    throw new Error("Invalid AniList token");
  }

  private async signInWithToken(token: string, expiresAt: number): Promise<AniListAccount> {
    const account = await this.client.viewer(token);
    const saved: AniListAccount = { ...account, expiresAt };
    this.patchSettings({ account: saved });
    if (this.playingInfo) void this.rematch(this.playingInfo);
    return saved;
  }

  signOut(expired = false) {
    clearAniListStorage();
    this.lastUndo = null;
    this.canUndo.set(false);
    this.settingsStore.set({ account: null, autoUpdate: true, askFirst: false, ignored: [] });
    this.state.set(this.client.configured ? { kind: "signedOut" } : { kind: "notConfigured" });
    if (expired) this.notice.set(makeNotice("Signed out of AniList; sign in again"));
  }

  savePrefs(autoUpdate: boolean, askFirst: boolean) {
    this.patchSettings({ autoUpdate, askFirst });
  }

  ignore(seriesKey: string, ignored: boolean) {
    const cur = this.settingsStore.value.ignored;
    const next = ignored ? Array.from(new Set([...cur, seriesKey])) : cur.filter((k) => k !== seriesKey);
    this.patchSettings({ ignored: next });
    if (this.playingInfo?.seriesKey === seriesKey) void this.rematch(this.playingInfo);
  }

  playing(info: EpisodeInfo | null) {
    this.playingInfo = info;
    this.reportedKey = null;
    void this.rematch(info);
  }

  refresh() {
    void this.rematch(this.playingInfo);
  }

  /** Tracks playback progress and triggers 90% auto-updates independently of MAL. */
  tickPlayback(key: string | null, info: EpisodeInfo | null, position: number, duration: number, ended: boolean) {
    if (!key || !info || this.reportedKey === key) return;
    if (duration <= 0) return;
    if (ended || position >= duration * WATCHED_FRACTION) {
      this.reportedKey = key;
      void this.watched(info);
    }
  }

  /** Identifies the anime on AniList (for AniSkip `idMal` fallback when MAL isn't matched). */
  async identifyForSkip(info: EpisodeInfo): Promise<{ anime: { id: number }; episode: number } | null> {
    if (this.settingsStore.value.ignored.includes(info.seriesKey)) return null;
    const st = this.state.value;
    if (st.kind === "matched" && st.info.seriesKey === info.seriesKey && st.entry.idMal) {
      return { anime: { id: st.entry.idMal }, episode: st.episode };
    }
    try {
      const results = await this.client.search(info.title, 8);
      const best = this.pickBest(info, results);
      if (best?.idMal && info.episode != null) {
        return { anime: { id: best.idMal }, episode: info.episode };
      }
    } catch {
      // Skip fallback is best-effort.
    }
    return null;
  }

  async choose(info: EpisodeInfo, anime: AniListAnime, episode: number) {
    const offset = (info.episode ?? episode) - episode;
    this.mappings[mappingKey(info)] = { mediaId: anime.id, offset };
    saveMappings(this.mappings);
    if (this.playingInfo?.seriesKey === info.seriesKey) {
      this.state.set({ kind: "matched", info, episode, entry: anime, candidates: [anime] });
    }
  }

  async update(anime: AniListAnime, patch: { status?: AniListStatus; progress?: number; score?: number; repeat?: number }) {
    const saved = await this.client.saveEntry(anime.id, patch);
    const cur = this.state.value;
    if (cur.kind === "matched" && cur.entry.id === anime.id) {
      this.state.set({ ...cur, entry: { ...cur.entry, listEntry: saved } });
    }
    return saved;
  }

  async watched(info: EpisodeInfo) {
    const prefs = this.settingsStore.value;
    if (!this.client.configured || !this.signedIn || !prefs.autoUpdate || prefs.ignored.includes(info.seriesKey)) return;
    const cur = this.state.value;
    if (cur.kind !== "matched" || cur.info.seriesKey !== info.seriesKey) return;
    if (prefs.askFirst) {
      this.notice.set(
        makeNotice(`Mark episode ${cur.episode} of ${cur.entry.title} watched on AniList?`, {
          confirm: () => void this.record(cur.entry, cur.episode),
        }),
      );
    } else {
      await this.record(cur.entry, cur.episode);
    }
  }

  async record(anime: AniListAnime, episode: number) {
    const before = anime.listEntry;
    const prev = before?.progress ?? 0;
    const completedBefore = before?.status === "COMPLETED";
    const rewatching = before?.status === "REPEATING" || completedBefore;
    const nextProgress = completedBefore ? episode : Math.max(prev, episode);
    if (!completedBefore && prev >= episode && before?.status === "CURRENT") return;
    const finished = anime.episodes > 0 && nextProgress >= anime.episodes;
    const status: AniListStatus = finished ? "COMPLETED" : rewatching ? "REPEATING" : "CURRENT";
    const saved = await this.update(anime, {
      status,
      progress: nextProgress,
      repeat: finished && rewatching ? (before?.repeat ?? 0) + 1 : undefined,
    });
    const undo = () => {
      this.lastUndo = null;
      this.canUndo.set(false);
      void (async () => {
        if (before) {
          await this.update(anime, { status: before.status ?? undefined, progress: before.progress, score: before.score, repeat: before.repeat });
        } else if (saved.id > 0) {
          await this.client.deleteEntry(saved.id);
        }
        this.notice.set(makeNotice(`Undid AniList update for ${anime.title}`));
      })();
    };
    this.lastUndo = undo;
    this.canUndo.set(true);
    this.notice.set(
      makeNotice(`AniList: ${finished ? "Completed" : `Ep ${nextProgress} of`} ${anime.title}`, { undo }),
    );
  }

  undo() {
    this.lastUndo?.();
  }

  private async rematch(info: EpisodeInfo | null) {
    if (!this.client.configured) {
      this.state.set({ kind: "notConfigured" });
      return;
    }
    if (!this.signedIn) {
      this.state.set({ kind: "signedOut" });
      return;
    }
    if (!info) {
      this.state.set({ kind: "idle" });
      return;
    }
    if (this.settingsStore.value.ignored.includes(info.seriesKey)) {
      this.state.set({ kind: "ignored", info });
      return;
    }
    this.state.set({ kind: "matching", info });
    try {
      const results = await this.client.search(info.title, 10);
      if (this.playingInfo !== info) return;
      const mapped = this.mappings[mappingKey(info)];
      if (mapped) {
        const hit = results.find((a) => a.id === mapped.mediaId);
        if (hit) {
          const ep = Math.max(1, (info.episode ?? 1) - mapped.offset);
          this.state.set({ kind: "matched", info, episode: ep, entry: hit, candidates: results });
          return;
        }
      }
      const best = this.pickBest(info, results);
      if (best) {
        this.state.set({ kind: "matched", info, episode: info.episode ?? 1, entry: best, candidates: results });
      } else if (results.length > 0) {
        this.state.set({ kind: "unsure", info, candidates: results });
      } else {
        this.state.set({ kind: "idle" });
      }
    } catch (e) {
      if (this.playingInfo === info) {
        this.state.set({ kind: "failed", info, message: e instanceof Error ? e.message : String(e) });
      }
    }
  }

  private pickBest(info: EpisodeInfo, results: AniListAnime[]): AniListAnime | null {
    if (results.length === 0) return null;
    const target = norm(info.title);
    const exact = results.find((a) => norm(a.title) === target || (a.englishTitle && norm(a.englishTitle) === target));
    if (exact && (info.season == null || info.season <= 1)) return exact;
    if (info.season && info.season > 1) {
      const sHit = results.find((a) => {
        const t = `${a.title} ${a.englishTitle ?? ""}`.toLowerCase();
        return t.includes(`season ${info.season}`) || t.includes(` ${info.season}`);
      });
      if (sHit) return sHit;
    }
    return exact ?? null;
  }

  private patchSettings(patch: Partial<AniListSettings>) {
    const next = { ...this.settingsStore.value, ...patch };
    this.settingsStore.set(next);
    saveAniListSettings(next);
  }
}
