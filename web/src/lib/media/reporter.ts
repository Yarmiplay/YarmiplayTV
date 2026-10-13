import type { ServerCopy } from "./locator";
import { messageOf, type MediaSource, type PlaybackReport, type ReportState } from "./mediaSource";

/** How often a playing file's position is reported. */
export const HEARTBEAT_MS = 10_000;
/** Played this far, the file is marked watched. */
export const WATCHED_FRACTION = 0.9;

/** What the room is playing, as far as reporting goes (the apps' NowPlaying). */
export interface ReportedMedia {
  /** Identifies the playback: the URL (or any key) the player was given for it. */
  url: string;
  /** Server items that are this file (whichever copy is streamed); playback is reported to each. Empty for local files. */
  copies: ServerCopy[];
  /** The duration the server gave, until the player knows its own. */
  durationHint: number;
}

/** The player as the reporter sees it (the apps' PlaybackState); the web player's `loaded`/`ended` map to [fileLoaded]/[eofReached]. */
export interface ReportedPlayer {
  /** What the player has open, comparable with [ReportedMedia.url]; null when nothing. */
  url: string | null;
  fileLoaded: boolean;
  paused: boolean;
  seeking: boolean;
  position: number;
  duration: number;
  eofReached: boolean;
}

export interface ReporterOptions {
  /** A fresh id for each playback; random UUIDs by default. */
  newSessionId?: () => string;
  /** A server that couldn't take a report; it's only logged, playback goes on. */
  onError?: (source: MediaSource, state: ReportState, error: unknown) => void;
}

const copyKey = (c: ServerCopy) => `${c.sourceKey}\n${c.itemId}`;

class Session {
  readonly started = new Set<string>();
  readonly watched = new Set<string>();
  copies: ServerCopy[] = [];
  paused = true;
  seeking = false;
  position = 0;
  duration = 0;

  constructor(
    readonly url: string,
    readonly id: string,
  ) {}
}

/**
 * Tells every server that has the playing file (see [ReportedMedia.copies]) how far playback got: start, pause and
 * resume, settled seeks, a heartbeat while playing, and stop; and marks it watched once 90% has been played
 * (PlaybackReporter.kt). Never touches the player, so the room's position is never pulled to a server's; a failing
 * server only reaches [ReporterOptions.onError].
 *
 * Driven by calls instead of coroutines: [update] on every change of what's playing or of the player's state, and
 * [heartbeat] every [HEARTBEAT_MS] (or [startHeartbeat] to have a timer do it).
 */
export class PlaybackReporter {
  private session: Session | null = null;
  private _enabled = true;
  private readonly inFlight = new Set<Promise<void>>();
  private readonly newSessionId: () => string;
  private readonly onError: (source: MediaSource, state: ReportState, error: unknown) => void;

  constructor(
    /** The servers signed in to right now. */
    private readonly servers: () => readonly MediaSource[],
    opts: ReporterOptions = {},
  ) {
    this.newSessionId = opts.newSessionId ?? (() => crypto.randomUUID());
    this.onError = opts.onError ?? ((source, state, e) => console.warn(`Couldn't report ${state} to ${source.displayName}: ${messageOf(e)}`));
  }

  get enabled(): boolean {
    return this._enabled;
  }

  /** Off, nothing more is sent (the current playback isn't even stopped); on again, the next update starts afresh. */
  set enabled(value: boolean) {
    this._enabled = value;
    if (!value) this.session = null;
  }

  /** Call whenever what's playing or the player's state changes. */
  update(np: ReportedMedia | null, state: ReportedPlayer): void {
    const current = this.session;
    if (current) {
      const playerMovedOn = state.url !== current.url || !state.fileLoaded;
      if (np?.url !== current.url || playerMovedOn) {
        if (state.eofReached && state.url === current.url) current.position = current.duration;
        this.stop(current);
      }
    }
    if (!this._enabled || np === null || np.copies.length === 0 || !state.fileLoaded || state.url !== np.url) return;
    let s = this.session;
    if (!s) {
      s = new Session(np.url, this.newSessionId());
      s.paused = state.paused;
      this.session = s;
    }
    s.copies = np.copies;
    s.duration = state.duration > 0 ? state.duration : np.durationHint;
    const wasSeeking = s.seeking;
    s.seeking = state.seeking;
    if (!state.seeking) s.position = state.position;
    if (state.eofReached) s.position = s.duration;

    const session = s;
    if (session.copies.some((c) => !session.started.has(copyKey(c)))) this.send(session, "STARTED", true);
    const playingOrPaused: ReportState = session.paused ? "PAUSED" : "PLAYING";
    if (state.paused !== session.paused) {
      session.paused = state.paused;
      this.send(session, session.paused ? "PAUSED" : "PLAYING");
    } else if (wasSeeking && !state.seeking) {
      this.send(session, playingOrPaused);
    } else if (this.needsWatched(session)) {
      this.send(session, playingOrPaused);
    }
  }

  /** The periodic progress report while playing; call every [HEARTBEAT_MS]. */
  heartbeat(): void {
    const s = this.session;
    if (s && !s.paused) this.send(s, "PLAYING");
  }

  /** Calls [heartbeat] every [intervalMs] until the returned function is called. */
  startHeartbeat(intervalMs = HEARTBEAT_MS): () => void {
    const timer = setInterval(() => this.heartbeat(), intervalMs);
    return () => clearInterval(timer);
  }

  /** Resolves once every report sent so far has been answered (or failed). */
  async settled(): Promise<void> {
    while (this.inFlight.size > 0) await Promise.all([...this.inFlight]);
  }

  private needsWatched(s: Session): boolean {
    return s.duration > 0 && s.position >= s.duration * WATCHED_FRACTION && s.copies.some((c) => !s.watched.has(copyKey(c)));
  }

  private stop(s: Session) {
    this.session = null;
    this.send(s, "STOPPED");
  }

  /** One report per copy; [onlyNew] sends STARTED just to copies that haven't had it. */
  private send(s: Session, state: ReportState, onlyNew = false) {
    const markWatched = this.needsWatched(s);
    const targets = onlyNew
      ? s.copies.filter((c) => !s.started.has(copyKey(c)))
      : s.copies.filter((c) => s.started.has(copyKey(c)) || state === "STARTED");
    for (const copy of targets) {
      const source = this.servers().find((x) => x.key === copy.sourceKey);
      if (!source) continue;
      const key = copyKey(copy);
      if (state === "STARTED") s.started.add(key);
      const watched = markWatched && !s.watched.has(key);
      if (watched) s.watched.add(key);
      const report: PlaybackReport = {
        itemId: copy.itemId,
        sessionId: s.id,
        state,
        positionSeconds: s.position,
        durationSeconds: s.duration,
        markWatched: watched,
      };
      const sent = (async () => {
        try {
          await source.reportPlayback(report);
        } catch (e) {
          this.onError(source, state, e);
        }
      })();
      this.inFlight.add(sent);
      void sent.finally(() => this.inFlight.delete(sent));
    }
  }
}
