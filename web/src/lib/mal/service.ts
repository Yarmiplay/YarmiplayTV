import { Store } from "../store";
import { MalCache } from "./cache";
import { MAL_UNAVAILABLE, MalClient, MalError, type RandomInts } from "./client";
import { sameEpisode, type EpisodeInfo } from "./episode";
import { MalMatcher, type MalMatch } from "./matcher";
import { restoring, statusValue, type MalAnime, type MalListStatus, type MalListUpdate, type MalQueued, type MalStatus } from "./models";
import { MalStore, type KeyValueStorage } from "./storage";

/** What the MyAnimeList panel shows for an episode. */
export type MalState =
  /** This build has no Client ID. */
  | { kind: "notConfigured" }
  | { kind: "signedOut" }
  /** Nothing playing, or not an episode of a show. */
  | { kind: "idle" }
  | { kind: "ignored"; info: EpisodeInfo }
  | { kind: "matching"; info: EpisodeInfo }
  /** [entry] is the matched anime with your list status as it is now. */
  | { kind: "matched"; info: EpisodeInfo; match: MalMatch; entry: MalAnime }
  /** Not sure which entry this is: the user picks from [candidates]. */
  | { kind: "unsure"; info: EpisodeInfo; candidates: MalAnime[] }
  | { kind: "failed"; info: EpisodeInfo; message: string };

/**
 * A MyAnimeList message for the player: [text] with, when set, an Undo, a "pick the entry" or a "yes, update"
 * action. [at] is when it was made (milliseconds).
 */
export interface MalNotice {
  text: string;
  undo: (() => void) | null;
  pick: boolean;
  confirm: (() => void) | null;
  at: number;
}

/** A question stays up long enough to answer; anything else is a brief note in the corner. */
export const isQuestion = (n: MalNotice): boolean => n.confirm != null || n.pick;
export const noticeShowMillis = (n: MalNotice): number => (isQuestion(n) ? 30_000 : 2_000);

/** Starts [run] after [millis]; the function it returns stops it. */
export type Schedule = (millis: number, run: () => void) => () => void;

const defaultSchedule: Schedule = (millis, run) => {
  const id = setTimeout(run, millis);
  return () => clearTimeout(id);
};

/** Today in this device's time zone, `YYYY-MM-DD`, as MyAnimeList's list dates are. */
export function localToday(date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * Where MyAnimeList sends the sign-in back to: the callback page next to the app
 * (`https://tv.yarmiplay.com/watch/mal-callback.html` in production), registered with the app on MyAnimeList.
 */
export function malRedirectUri(origin: string = location.origin, base: string = import.meta.env.BASE_URL): string {
  return `${origin}${base.endsWith("/") ? base : `${base}/`}mal-callback.html`;
}

/** The sign-in under way, kept until the callback page answers. */
const SIGN_IN_KEY = "mal_sign_in";

export interface MalServiceOptions {
  client: MalClient;
  /** Where the account, matches, ignored shows and queue are kept (localStorage under `ytv:` in the app). */
  storage: KeyValueStorage;
  /** Where a sign-in under way is kept (sessionStorage in the app); [storage] when not given. */
  session?: KeyValueStorage;
  now?: () => number;
  today?: () => string;
  /** How the queue's retries are timed; [MalService.dispose] stops them. */
  schedule?: Schedule;
  log?: (message: string) => void;
}

/**
 * MyAnimeList for the app: the account, which entry the playing episode is, and the list updates (automatic when
 * an episode has been watched, with Undo; or by hand from the panel). Nothing is stored anywhere but this device,
 * and signing out deletes it all.
 */
export class MalService {
  readonly client: MalClient;
  /** What's kept for MyAnimeList (account, switches, matches, ignored shows, queue), for the settings screens. */
  readonly store: MalStore;
  readonly state: Store<MalState>;
  readonly notice = new Store<MalNotice | null>(null);
  /** Whether the last automatic update can still be undone. */
  readonly canUndo = new Store(false);

  private readonly session: KeyValueStorage;
  private readonly now: () => number;
  private readonly today: () => string;
  private readonly schedule: Schedule;
  private readonly log: (message: string) => void;
  private readonly catalog: MalCache;
  private readonly matcher: MalMatcher;
  /** Public data only (no list), for knowing what's playing without an account: see [identify]. */
  private readonly publicMatcher: MalMatcher;

  /** The last automatic update, until the next one: what Undo puts back. */
  private undoAction: (() => Promise<void>) | null = null;
  private playingInfo: EpisodeInfo | null = null;
  private matchJob: Promise<void> | null = null;
  /** Counts lookups, so one that's no longer wanted (another episode, signed out) is dropped. */
  private matchGeneration = 0;
  /** Watched before the match was known (or while unsure): applied once it is. */
  private watchedPending: EpisodeInfo | null = null;
  private queueLock: Promise<unknown> = Promise.resolve();
  private cancelRetry: (() => void) | null = null;
  private backoffMinutes = 1;
  private disposed = false;

  constructor(options: MalServiceOptions) {
    this.client = options.client;
    this.store = new MalStore(options.storage);
    this.session = options.session ?? options.storage;
    this.now = options.now ?? Date.now;
    this.today = options.today ?? (() => localToday());
    this.schedule = options.schedule ?? defaultSchedule;
    this.log = options.log ?? ((m) => console.warn(m));
    this.catalog = new MalCache(this.client, this.now);
    this.matcher = new MalMatcher(this.catalog);
    this.publicMatcher = new MalMatcher(new MalCache(this.client, this.now));
    this.state = new Store<MalState>(this.client.configured ? { kind: "idle" } : { kind: "notConfigured" });

    this.client.tokens = this.store.value.account?.tokens ?? null;
    this.client.onTokens = (tokens) => {
      const account = this.store.value.account;
      if (tokens == null) this.signOut(true);
      else if (account != null) this.store.saveAccount({ ...account, tokens });
    };
    if (this.signedIn && this.store.value.queue.length > 0) this.scheduleRetry(0);
  }

  get signedIn(): boolean {
    return this.client.tokens != null;
  }

  /** Stops the queue's retries; the service isn't used after this. */
  dispose() {
    this.disposed = true;
    this.cancelRetry?.();
    this.cancelRetry = null;
    this.matchGeneration++;
  }

  // --- Account -----------------------------------------------------------------------------

  /**
   * Starts signing in: the MyAnimeList page to open (in a popup, whose callback page posts the answer back), with
   * the PKCE verifier and `state` kept until [completeSignIn].
   */
  beginSignIn(redirectUri: string = malRedirectUri(), random?: RandomInts): string {
    const verifier = MalClient.newVerifier(random);
    const state = MalClient.newState(random);
    try {
      this.session.setItem(SIGN_IN_KEY, JSON.stringify({ verifier, state, redirectUri }));
    } catch {
      // Without storage the sign-in can't be finished; MyAnimeList's page still opens and says nothing is lost.
    }
    return this.client.authorizeUrl(state, verifier, redirectUri);
  }

  /** Finishes the sign-in [beginSignIn] started, with the `code` and `state` its callback page got. */
  async completeSignIn(code: string, state: string): Promise<void> {
    let pending: { verifier?: unknown; state?: unknown; redirectUri?: unknown } | null = null;
    try {
      pending = JSON.parse(this.session.getItem(SIGN_IN_KEY) ?? "null");
    } catch {
      pending = null;
    }
    if (!pending || typeof pending.verifier !== "string" || typeof pending.redirectUri !== "string" || pending.state !== state) {
      throw new MalError("That MyAnimeList sign-in isn't this one; sign in again");
    }
    try {
      this.session.removeItem(SIGN_IN_KEY);
    } catch {
      // Used once either way: its state won't match another answer.
    }
    await this.signIn(code, pending.verifier, pending.redirectUri);
  }

  /** Finishes signing in with the code MyAnimeList's page sent back. */
  async signIn(code: string, verifier: string, redirectUri: string = malRedirectUri()): Promise<void> {
    const tokens = await this.client.exchange(code, verifier, redirectUri);
    const me = await this.client.me();
    this.store.saveAccount({ tokens, userName: me.name, picture: me.picture });
    if (this.playingInfo) this.rematch(this.playingInfo);
    if (this.store.value.queue.length > 0) this.scheduleRetry(0);
  }

  /** Signs out and deletes everything MyAnimeList on this device: the account, matches, queue and cached content. */
  signOut(expired = false) {
    this.client.tokens = null;
    this.catalog.clear();
    this.cancelRetry?.();
    this.cancelRetry = null;
    this.setUndo(null);
    this.watchedPending = null;
    this.matchGeneration++;
    this.state.set(this.client.configured ? { kind: "signedOut" } : { kind: "notConfigured" });
    this.notice.set(expired ? this.makeNotice("Signed out of MyAnimeList; sign in again in Settings") : null);
    this.store.clear();
  }

  /** Update the list by itself when an episode has been watched, and whether to ask first. */
  savePrefs(autoUpdate: boolean, askFirst: boolean) {
    this.store.savePrefs(autoUpdate, askFirst);
  }

  // --- Playing ---------------------------------------------------------------------------------

  /** The episode now playing (null for none, or something that isn't an episode). */
  playing(info: EpisodeInfo | null) {
    if (sameEpisode(info, this.playingInfo)) return;
    this.playingInfo = info;
    this.watchedPending = null;
    this.rematch(info);
  }

  /** Looks the playing episode up again (after an error, or a change made elsewhere). */
  refresh() {
    this.rematch(this.playingInfo);
  }

  private isIgnored(info: EpisodeInfo) {
    return this.store.value.ignored.includes(info.seriesKey);
  }

  private rematch(info: EpisodeInfo | null) {
    const generation = ++this.matchGeneration;
    let next: MalState;
    if (!this.client.configured) next = { kind: "notConfigured" };
    else if (!this.signedIn) next = { kind: "signedOut" };
    else if (info == null) next = { kind: "idle" };
    else if (this.isIgnored(info)) next = { kind: "ignored", info };
    else next = { kind: "matching", info };
    this.state.set(next);
    if (next.kind !== "matching" || info == null) return;
    this.matchJob = this.lookup(info).then((s) => {
      if (generation === this.matchGeneration) this.state.set(s);
    });
  }

  /** What [info] is on MyAnimeList (for the playing episode, or one picked in the library). */
  async lookup(info: EpisodeInfo): Promise<MalState> {
    try {
      if (!this.client.configured) return { kind: "notConfigured" };
      if (!this.signedIn) return { kind: "signedOut" };
      if (this.isIgnored(info)) return { kind: "ignored", info };
      // The browser asks MyAnimeList through a YarmiplayServerTV; without one, say so rather than find nothing.
      if (!this.client.available) return { kind: "failed", info, message: MAL_UNAVAILABLE };
      const match = await this.matcher.match(info, this.store.value.mappings[MalService.mappingKey(info)] ?? null);
      if (match == null) return { kind: "idle" };
      if (match.sure) return { kind: "matched", info, match, entry: await this.catalog.anime(match.anime.id) };
      const candidates = [match.anime, ...match.candidates].filter((a, i, all) => all.findIndex((b) => b.id === a.id) === i);
      return { kind: "unsure", info, candidates };
    } catch (e) {
      return { kind: "failed", info, message: e instanceof Error && e.message ? e.message : "MyAnimeList lookup failed" };
    }
  }

  /**
   * Which MyAnimeList anime and episode [info] surely is, signed in or not (e.g. for skipping its opening); null
   * when unsure, unknown, a show the user doesn't track, or a build without a Client ID. Uses the playing
   * episode's match when there is one rather than looking it up again.
   */
  async identify(info: EpisodeInfo): Promise<MalMatch | null> {
    if (!this.client.configured || this.isIgnored(info)) return null;
    const s = this.state.value;
    if (!this.client.available && s.kind !== "matched") return null;
    if (s.kind === "matching" && sameEpisode(s.info, info)) await this.matchJob;
    const now = this.state.value;
    if (now.kind === "matched" && sameEpisode(now.info, info)) return now.match;
    const matcher = this.signedIn ? this.matcher : this.publicMatcher;
    const match = await matcher.match(info, this.store.value.mappings[MalService.mappingKey(info)] ?? null);
    return match?.sure ? match : null;
  }

  /** The playing episode has been watched (90% through). */
  async watched(info: EpisodeInfo): Promise<void> {
    const prefs = this.store.value;
    if (!this.client.configured || !this.signedIn || !prefs.autoUpdate || this.isIgnored(info)) return;
    await this.matchJob;
    const s = this.state.value;
    if (s.kind === "matched" && sameEpisode(s.info, info)) {
      if (this.store.value.askFirst) {
        this.notice.set(
          this.makeNotice(`Mark episode ${s.match.episode} of ${s.entry.title} watched on MyAnimeList?`, {
            confirm: () => void this.record(s.entry, s.match.episode, true),
          }),
        );
      } else {
        await this.record(s.entry, s.match.episode, true);
      }
    } else if (s.kind === "unsure" && sameEpisode(s.info, info)) {
      this.watchedPending = info;
      this.notice.set(this.makeNotice(`Which MyAnimeList entry is ${info.title}? Pick it in the MyAnimeList panel`, { pick: true }));
    }
  }

  // --- Choosing the entry ----------------------------------------------------------------------

  /** The user says [info] is episode [episode] of [anime]: remembered for the show, and applied if it was watched. */
  async choose(info: EpisodeInfo, anime: MalAnime, episode: number): Promise<void> {
    const key = MalService.mappingKey(info);
    this.store.saveMapping(key, { malId: anime.id, offset: (info.episode ?? episode) - episode });
    const full = await this.catalog.anime(anime.id).catch(() => anime);
    if (sameEpisode(info, this.playingInfo)) {
      this.matchGeneration++;
      this.state.set({ kind: "matched", info, match: { anime: full, episode, sure: true, candidates: [] }, entry: full });
    }
    if (sameEpisode(this.watchedPending, info)) {
      this.watchedPending = null;
      await this.record(full, episode, true);
    }
  }

  async search(query: string): Promise<MalAnime[]> {
    try {
      return await this.client.search(query, 15);
    } catch {
      return [];
    }
  }

  /**
   * Shows whose titles match [query], for the search on the MyAnimeList screens: each with your list entry when
   * signed in. Failures throw, so the search can say why.
   */
  findShows(query: string): Promise<MalAnime[]> {
    return this.client.search(query, 20, true);
  }

  /** Takes [anime] off your list; false (with a notice) when MyAnimeList didn't. */
  async remove(anime: MalAnime): Promise<boolean> {
    try {
      await this.client.deleteStatus(anime.id);
      this.catalog.forget(anime.id);
      this.refreshState({ ...anime, myListStatus: null });
      return true;
    } catch (e) {
      this.notice.set(this.makeNotice(`MyAnimeList didn't remove ${anime.title}: ${errorText(e)}`));
      return false;
    }
  }

  /** Your MyAnimeList entries with [status] (for the MyAnimeList screen); [fresh] reads them again. */
  myList(status: MalStatus, fresh = false): Promise<[MalAnime, MalListStatus][]> {
    return this.catalog.list(status, fresh);
  }

  /** An anime's details (cached for at most 24 hours), e.g. a matched show's title. */
  anime(id: number): Promise<MalAnime> {
    return this.catalog.anime(id);
  }

  /** Stop (or start again) tracking [seriesKey]. */
  ignore(seriesKey: string, ignored: boolean, name = "") {
    const current = this.store.value;
    const set = ignored ? [...new Set([...current.ignored, seriesKey])] : current.ignored.filter((k) => k !== seriesKey);
    const names = ignored && name.trim() !== "" ? { ...current.ignoredNames, [seriesKey]: name } : current.ignoredNames;
    this.store.saveIgnored(set, names);
    const playing = this.playingInfo;
    if (playing && playing.seriesKey === seriesKey) this.rematchAfterSave(playing, ignored);
  }

  private rematchAfterSave(info: EpisodeInfo, ignored: boolean) {
    if (ignored) {
      this.matchGeneration++;
      this.state.set({ kind: "ignored", info });
    } else {
      this.rematch(info);
    }
  }

  // --- List updates ----------------------------------------------------------------------------

  /**
   * Episode [episode] of [anime] has been watched: progress only goes up; the first episode starts it (watching,
   * with today's start date), the last completes it (with a finish date), and watching a completed show again
   * marks it as a rewatch.
   */
  async record(anime: MalAnime, episode: number, automatic: boolean): Promise<void> {
    // The list may have changed elsewhere since the match (another device, the website): read it again.
    let current = anime;
    try {
      current = await this.client.anime(anime.id);
      this.catalog.put(current);
    } catch {
      // Then as it was matched.
    }
    const before = current.myListStatus;
    const update = MalService.progressUpdate(before, episode, current.numEpisodes, this.today());
    if (!update) return;
    await this.send(current, update, before, automatic);
  }

  /** Changes [anime]'s list entry now and returns it as it is after (for lists that show it). */
  async update(anime: MalAnime, update: MalListUpdate): Promise<MalListStatus | null> {
    await this.send(anime, update, anime.myListStatus, false);
    try {
      return (await this.catalog.anime(anime.id)).myListStatus;
    } catch {
      return null;
    }
  }

  /** Changes [anime]'s list entry by hand (status, score, episodes, rewatching). */
  edit(anime: MalAnime, update: MalListUpdate): Promise<void> {
    return this.send(anime, update, anime.myListStatus, false);
  }

  private async send(anime: MalAnime, update: MalListUpdate, before: MalListStatus | null, automatic: boolean): Promise<void> {
    try {
      const after = await this.client.updateStatus(anime.id, update);
      const updated: MalAnime = { ...anime, myListStatus: after };
      this.catalog.put(updated);
      this.refreshState(updated);
      if (automatic) {
        const undo = before ? restoring(before) : null;
        const undoThis = async () => {
          if (undo) await this.send(updated, undo, after, false);
          else await this.client.deleteStatus(anime.id).catch(() => undefined);
          this.setUndo(null);
          this.notice.set(this.makeNotice("MAL: undone"));
          if (!undo) this.refreshState({ ...updated, myListStatus: null });
        };
        this.setUndo(undoThis);
        const of = anime.numEpisodes > 0 ? `/${anime.numEpisodes}` : "";
        const completed = statusValue(after) === "completed" && statusValue(before) !== "completed";
        // The notice's Undo is for this update, and only while it's still the last one.
        const undoNow = () => void (this.undoAction === undoThis ? undoThis() : undefined);
        this.notice.set(this.makeNotice(`MAL: ${anime.title} · ` + (completed ? "completed" : `ep ${after.watchedEpisodes}${of}`), { undo: undoNow }));
      }
      if (this.store.value.queue.length > 0) this.scheduleRetry(0);
    } catch (e) {
      if (e instanceof MalError) {
        if (e.httpCode === 401 && !this.signedIn) return;
        if (retryLater(e)) {
          await this.enqueue({ animeId: anime.id, update });
          this.notice.set(this.makeNotice("MyAnimeList can't be reached; the update will be sent later"));
          return;
        }
      }
      this.notice.set(this.makeNotice(`MyAnimeList didn't take the update: ${errorText(e)}`));
    }
  }

  /** Puts back what the last automatic update changed. */
  undo(): Promise<void> {
    return this.undoAction?.() ?? Promise.resolve();
  }

  dismissNotice() {
    this.notice.set(null);
  }

  private setUndo(action: (() => Promise<void>) | null) {
    this.undoAction = action;
    this.canUndo.set(action != null);
  }

  private makeNotice(text: string, extra: Partial<Pick<MalNotice, "undo" | "pick" | "confirm">> = {}): MalNotice {
    return { text, undo: extra.undo ?? null, pick: extra.pick ?? false, confirm: extra.confirm ?? null, at: this.now() };
  }

  private refreshState(updated: MalAnime) {
    const s = this.state.value;
    if (s.kind === "matched" && s.entry.id === updated.id) this.state.set({ ...s, entry: updated });
  }

  // --- Offline queue ---------------------------------------------------------------------------

  /** Runs [work] after the queue's other work, one at a time. */
  private locked<T>(work: () => Promise<T>): Promise<T> {
    const run = this.queueLock.then(work, work);
    this.queueLock = run.catch(() => undefined);
    return run;
  }

  private enqueue(item: MalQueued): Promise<void> {
    return this.locked(async () => {
      const queue = this.store.value.queue.filter(
        (q) => !(q.animeId === item.animeId && q.update.watchedEpisodes != null && item.update.watchedEpisodes != null),
      );
      this.store.saveQueue([...queue, item]);
      this.scheduleRetry(this.backoffMinutes);
    });
  }

  private scheduleRetry(minutes: number) {
    this.cancelRetry?.();
    if (this.disposed) return;
    this.cancelRetry = this.schedule(minutes * 60_000, () => {
      this.cancelRetry = null;
      void this.flushQueue();
    });
  }

  /** Sends the queued updates in order; stops at the first that can't go yet and backs off (1, 2, 4 … 30 minutes). */
  async flushQueue(): Promise<void> {
    if (!this.signedIn) return;
    await this.locked(async () => {
      let queue = this.store.value.queue;
      while (queue.length > 0) {
        const next = queue[0];
        try {
          await this.client.updateStatus(next.animeId, next.update);
          this.catalog.forget(next.animeId);
        } catch (e) {
          if (e instanceof MalError && retryLater(e)) {
            this.backoffMinutes = Math.min(this.backoffMinutes * 2, 30);
            this.scheduleRetry(this.backoffMinutes);
            return;
          }
          this.log(`MalService: dropping a queued MyAnimeList update: ${errorText(e)}`);
        }
        queue = queue.slice(1);
        this.store.saveQueue(queue);
      }
      this.backoffMinutes = 1;
    });
  }

  /** Saved matches are per show and season: seasons of a show can be different entries. */
  static mappingKey(info: EpisodeInfo): string {
    return `${info.seriesKey}|${info.season ?? 0}`;
  }

  /**
   * The update for having watched [episode] of an entry with [total] episodes (0 = unknown), from list status
   * [before]; null when there's nothing to change (an episode already counted).
   */
  static progressUpdate(before: MalListStatus | null, episode: number, total: number, today: string): MalListUpdate | null {
    const status = statusValue(before);
    const watched = before?.watchedEpisodes ?? 0;
    const rewatching = status === "completed" || before?.isRewatching === true;
    if (rewatching) {
      const finished = total >= 1 && total <= episode;
      if (finished) return { isRewatching: false, timesRewatched: (before?.timesRewatched ?? 0) + 1, watchedEpisodes: total };
      if (before?.isRewatching !== true) return { isRewatching: true, watchedEpisodes: episode };
      if (episode > watched) return { watchedEpisodes: episode };
      return null;
    }
    if (episode <= watched && status === "watching") return null;
    const progress = Math.max(watched, episode);
    const complete = total >= 1 && total <= progress;
    const update: MalListUpdate = { status: complete ? "completed" : "watching", watchedEpisodes: progress };
    if (before?.startDate == null) update.startDate = today;
    if (complete && before?.finishDate == null) update.finishDate = today;
    return update;
  }
}

/** No answer, MyAnimeList busy (429) or failing (5xx): worth sending again later. */
const retryLater = (e: MalError): boolean => e.httpCode == null || e.httpCode === 429 || e.httpCode >= 500;

const errorText = (e: unknown): string => (e instanceof Error ? e.message : String(e));
