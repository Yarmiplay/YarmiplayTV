import { Store } from "../store";
import type { EpisodeInfo } from "../mal/episode";
import type { AniSkipClient } from "./aniskip";
import { chapterSkips } from "./chapterSkips";
import { SKIP_KINDS, type Chapter, type SkipKind, type SkipSegment } from "./segment";

/** "Skip opening" on the player for a moment, as the opening starts and it isn't skipped by itself. */
export interface SkipPrompt {
  segment: SkipSegment;
  label: string;
}

/** The playing file, as skipping needs it. */
export interface SkipFile {
  /** Identifies the file (its URL or name); a different key is a different file. */
  key: string;
  /** Which episode it is, when it's one; without its number neither AniSkip nor listening can help. */
  episode: EpisodeInfo | null;
}

/** The Playback settings skipping follows. */
export interface SkipSettings {
  autoSkipAlone: boolean;
  showSkipButton: boolean;
  detectSkips: boolean;
}

/** The room's skip setting: in a room, it decides; out of one, the Playback setting does. */
export interface RoomSkipSetting {
  inRoom: boolean;
  on: boolean;
}

/** Listens for the parts no other source knows; [signal] says when that's no longer wanted. */
export type SkipDetect = (kinds: SkipKind[], context: { file: SkipFile; duration: number; signal: AbortSignal }) => Promise<SkipSegment[]>;

export interface SkipServiceOptions {
  /** Moves playback (in a room, everyone's, like any seek). */
  seek: (position: number) => void;
  /** Whether this device may seek: false for a viewer who follows a managed room's operators. */
  canSeek: () => boolean;
  roomSkip: () => RoomSkipSetting;
  settings: () => SkipSettings;
  /** A brief note on the player ("Skipped the opening"). */
  notify: (text: string) => void;
  /** Milliseconds, as `Date.now()`. */
  now?: () => number;
  /** Which MyAnimeList anime and episode an episode surely is ([MalService.identify]); without it AniSkip isn't asked. */
  identify?: (info: EpisodeInfo) => Promise<{ anime: { id: number }; episode: number } | null>;
  aniSkip?: Pick<AniSkipClient, "skipTimes">;
  /** The media server's markers for the file (Jellyfin's media segments, Plex's intro and credits markers). */
  markers?: (file: SkipFile) => Promise<SkipSegment[]>;
  /** Listening for the parts nothing else knows; left out, nothing is listened for. */
  detect?: SkipDetect;
  log?: (message: string) => void;
}

/** How long the Skip button shows. */
export const PROMPT_MILLIS = 3_000;
const PROMPT_SECONDS = PROMPT_MILLIS / 1000;
/** An automatic skip happens while playback is within this many seconds of the part's start. */
const AUTO_WINDOW_SECONDS = 10;
const RETRY_MILLIS = 1_500;
const MAX_TRIES = 4;
const END_SLACK_SECONDS = 2;
/** The file's chapters come a moment after it loads: listening waits for them. */
export const CHAPTERS_SETTLE_MILLIS = 2_000;

const sameChapters = (a: readonly Chapter[] | null, b: readonly Chapter[]) =>
  a != null && a.length === b.length && a.every((c, i) => c.start === b[i].start && c.end === b[i].end && c.title === b[i].title);

/**
 * Skips anime openings and endings: by itself when the room (or, alone, the Playback setting) says so, else with a
 * Skip button shown for [PROMPT_MILLIS] as the part starts. Where they are comes, for each part, from the first of:
 * the file's own chapters ("Opening", "ED"…), the media server's markers, AniSkip's times for the episode
 * MyAnimeList says is playing, and, when none of those knows a part and the Playback setting allows it, listening
 * for it. Only a device that may seek does either (in a managed room, the operators); in a room the skip moves
 * everyone, like any seek. Each part is handled once per file, so seeking back into it plays it.
 *
 * It keeps no timers: the app tells it of a new file ([fileChanged]), of the file having loaded and its chapters
 * ([loaded]), and of playback ([tick], a few times a second while a file is open, paused or not); the Skip button
 * goes and listening starts on those ticks. [dispose] drops whatever is still under way.
 */
export class SkipService {
  /** The playing file's opening and ending, when known (also for the seek bar's marks). */
  readonly segments = new Store<SkipSegment[]>([]);
  /** The Skip button to show now, if any. */
  readonly prompt = new Store<SkipPrompt | null>(null);

  private readonly now: () => number;
  private readonly log: (message: string) => void;
  /** AniSkip's answers per episode and file length, also "nothing known"; kept in memory only. */
  private readonly known = new Map<string, SkipSegment[]>();

  private file: SkipFile | null = null;
  /** Counts files, so an answer for an earlier one is dropped. */
  private generation = 0;
  private duration = 0;
  private fileLoaded = false;
  private fromChapters: SkipSegment[] = [];
  private chaptersOf: readonly Chapter[] | null = null;
  private fromServer: SkipSegment[] = [];
  private fromAniSkip: SkipSegment[] = [];
  private fromDetected: SkipSegment[] = [];
  private markersDone = true;
  private aniSkipStarted = false;
  private aniSkipDone = true;
  /** When listening may start (the other sources have answered and the file loaded), then [CHAPTERS_SETTLE_MILLIS] on. */
  private detectReadyAt: number | null = null;
  private detectStarted = false;
  private detectAbort: AbortController | null = null;
  private detectSetting: boolean | null = null;
  private promptAt = 0;
  /** Parts handled in this file (skipped, offered or passed), by start time. */
  private readonly handled = new Set<number>();
  /** Automatic skips tried and when: the room can swallow a seek just after a file starts. */
  private readonly tries = new Map<number, { count: number; at: number }>();

  constructor(private readonly options: SkipServiceOptions) {
    this.now = options.now ?? Date.now;
    this.log = options.log ?? ((m) => console.warn(m));
  }

  /** What's playing changed (null for nothing). The same file again changes nothing. */
  fileChanged(file: SkipFile | null) {
    if ((file?.key ?? null) === (this.file?.key ?? null)) return;
    this.reset();
    this.file = file;
    if (!file) return;
    const generation = this.generation;
    const markers = this.options.markers;
    if (markers) {
      this.markersDone = false;
      markers(file)
        .then((marked) => {
          if (generation !== this.generation || marked.length === 0) return;
          this.fromServer = marked;
          this.merge();
        })
        .catch((e) => this.log(`SkipService: no markers from the media server: ${message(e)}`))
        .finally(() => {
          if (generation !== this.generation) return;
          this.markersDone = true;
          this.armDetect();
        });
    }
    // Without the episode's number, neither AniSkip nor listening can tell which episode this is.
    this.aniSkipDone = !(file.episode && this.options.identify && this.options.aniSkip);
  }

  /**
   * The playing file has loaded, [duration] seconds long, with its [chapters] (again whenever they change: they can
   * come a moment after it loads).
   */
  loaded(duration: number, chapters: readonly Chapter[] = []) {
    if (!this.file) return;
    this.fileLoaded = true;
    if (duration > 0) this.duration = duration;
    if (!sameChapters(this.chaptersOf, chapters)) {
      this.chaptersOf = [...chapters];
      this.fromChapters = chapterSkips(chapters, this.duration);
      this.merge();
    }
    if (this.duration > 0 && !this.aniSkipStarted && !this.aniSkipDone) this.askAniSkip(this.file, this.duration);
    this.armDetect();
  }

  /** Playback is at [position] seconds; [paused] and [seeking] leave the parts alone. */
  tick(position: number, paused: boolean, seeking = false) {
    this.followDetectSetting();
    const shown = this.prompt.value;
    if (shown && this.now() - this.promptAt >= PROMPT_MILLIS) this.prompt.set(null);
    if (this.detectReadyAt != null && !this.detectStarted && this.now() - this.detectReadyAt >= CHAPTERS_SETTLE_MILLIS) this.startDetect();

    const segments = this.segments.value;
    if (segments.length === 0 || !this.file || !this.fileLoaded || paused || seeking) return;
    const p = this.prompt.value;
    if (p && (position < p.segment.start || position >= p.segment.end)) this.dismiss();
    const canSeek = this.options.canSeek();
    for (const segment of segments) {
      if (this.handled.has(segment.start)) continue;
      const inside = position >= segment.start && position < segment.end - 1;
      if (!inside) continue;
      const room = this.options.roomSkip();
      const settings = this.options.settings();
      const auto = room.inRoom ? room.on : settings.autoSkipAlone;
      if (!canSeek) this.handled.add(segment.start);
      else if (auto) this.autoSkip(segment, position);
      else if (position >= segment.start + PROMPT_SECONDS) this.handled.add(segment.start);
      else {
        this.handled.add(segment.start);
        if (settings.showSkipButton) this.offer(segment);
      }
    }
  }

  /** Skips the part the Skip button offers. */
  skipNow() {
    const p = this.prompt.value;
    if (!p) return;
    this.dismiss();
    this.jumpPast(p.segment);
  }

  /**
   * The Playback setting for listening may have changed: turned off, listening stops at once; turned on, it starts
   * for the playing file. [tick] looks too.
   */
  settingsChanged() {
    this.followDetectSetting();
  }

  /** Drops the file and whatever is still under way. */
  dispose() {
    this.reset();
    this.file = null;
  }

  private reset() {
    this.generation++;
    this.detectAbort?.abort();
    this.detectAbort = null;
    this.dismiss();
    this.handled.clear();
    this.tries.clear();
    this.duration = 0;
    this.fileLoaded = false;
    this.fromChapters = [];
    this.chaptersOf = null;
    this.fromServer = [];
    this.fromAniSkip = [];
    this.fromDetected = [];
    this.markersDone = true;
    this.aniSkipStarted = false;
    this.aniSkipDone = true;
    this.detectReadyAt = null;
    this.detectStarted = false;
    this.segments.set([]);
  }

  private dismiss() {
    this.prompt.set(null);
  }

  private askAniSkip(file: SkipFile, duration: number) {
    const info = file.episode;
    const { identify, aniSkip } = this.options;
    if (!info || !identify || !aniSkip) return;
    this.aniSkipStarted = true;
    const generation = this.generation;
    (async () => {
      const match = await identify(info);
      if (!match) return;
      const key = `${match.anime.id}/${match.episode}/${Math.round(duration)}`;
      let segments = this.known.get(key);
      if (!segments) {
        segments = await aniSkip.skipTimes(match.anime.id, match.episode, duration);
        this.known.set(key, segments);
      }
      if (generation !== this.generation) return;
      this.fromAniSkip = segments;
      this.merge();
    })()
      .catch((e) => this.log(`SkipService: no skip times for ${info.title} ${info.episode}: ${message(e)}`))
      .finally(() => {
        if (generation !== this.generation) return;
        this.aniSkipDone = true;
        this.armDetect();
      });
  }

  /** Listening may start once the other sources have answered and the file has loaded: from then, the chapters' wait. */
  private armDetect() {
    if (this.detectReadyAt != null || this.detectStarted || !this.wantsDetect()) return;
    if (!this.markersDone || !this.aniSkipDone || !this.fileLoaded || this.duration <= 0) return;
    this.detectReadyAt = this.now();
  }

  private wantsDetect(): boolean {
    return this.options.detect != null && this.options.settings().detectSkips && this.file?.episode?.episode != null;
  }

  private followDetectSetting() {
    if (!this.options.detect) return;
    const on = this.options.settings().detectSkips;
    if (on === this.detectSetting) return;
    const first = this.detectSetting == null;
    this.detectSetting = on;
    if (first && on) return;
    // Turned off, listening stops at once; turned on, it starts again for the playing file.
    this.detectAbort?.abort();
    this.detectAbort = null;
    this.detectReadyAt = null;
    this.detectStarted = false;
    if (on) this.armDetect();
  }

  /**
   * Listens for the parts of the file no other source knows; only on a device that may seek, and only while the
   * Playback setting is on.
   */
  private startDetect() {
    this.detectStarted = true;
    const detect = this.options.detect;
    const file = this.file;
    if (!detect || !file || !this.wantsDetect() || !this.options.canSeek()) return;
    const missing = SKIP_KINDS.filter((kind) => ![this.fromChapters, this.fromServer, this.fromAniSkip].some((source) => source.some((s) => s.kind === kind)));
    if (missing.length === 0) return;
    const abort = new AbortController();
    this.detectAbort = abort;
    const generation = this.generation;
    detect([...missing], { file, duration: this.duration, signal: abort.signal })
      .then((found) => {
        if (generation !== this.generation || abort.signal.aborted) return;
        this.fromDetected = found.filter((s) => missing.includes(s.kind));
        this.merge();
      })
      .catch((e) => {
        if (!abort.signal.aborted) this.log(`SkipService: couldn't listen for the opening and ending: ${message(e)}`);
      })
      .finally(() => {
        if (this.detectAbort === abort) this.detectAbort = null;
      });
  }

  /** For each part, the first source that knows it: the file's chapters, the server's markers, AniSkip, listening. */
  private merge() {
    const sources = [this.fromChapters, this.fromServer, this.fromAniSkip, this.fromDetected];
    const merged: SkipSegment[] = [];
    for (const kind of SKIP_KINDS) {
      for (const source of sources) {
        const found = source.find((s) => s.kind === kind);
        if (found) {
          merged.push(found);
          break;
        }
      }
    }
    this.segments.set(merged.sort((a, b) => a.start - b.start));
  }

  private autoSkip(segment: SkipSegment, position: number) {
    // Only from its start: seeking into the middle of an opening means wanting to see it.
    if (position >= segment.start + AUTO_WINDOW_SECONDS) {
      this.handled.add(segment.start);
      return;
    }
    const { count, at } = this.tries.get(segment.start) ?? { count: 0, at: 0 };
    if (count > 0 && this.now() - at < RETRY_MILLIS) return;
    if (count >= MAX_TRIES) {
      this.handled.add(segment.start);
      return;
    }
    this.tries.set(segment.start, { count: count + 1, at: this.now() });
    this.jumpPast(segment);
    if (count === 0) this.options.notify(`Skipped the ${segment.kind}`);
  }

  private offer(segment: SkipSegment) {
    this.promptAt = this.now();
    this.prompt.set({ segment, label: `Skip ${segment.kind}` });
  }

  /** To the end of [segment]; an ending that runs to the end of the file goes to just before it, so the file ends as usual. */
  private jumpPast(segment: SkipSegment) {
    const duration = this.duration;
    const target = duration > 0 && segment.end >= duration - END_SLACK_SECONDS ? duration - 0.5 : segment.end;
    this.options.seek(target);
  }
}

const message = (e: unknown): string => (e instanceof Error ? e.message : String(e));
