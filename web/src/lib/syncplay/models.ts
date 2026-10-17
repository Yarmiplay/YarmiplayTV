/** Syncplay's protocol values and the desktop client's timing, as in Models.kt. */
export const Constants = {
  CLIENT_VERSION: "1.2.255",
  REAL_VERSION: "1.7.6",
  DEFAULT_PORT: 8999,

  SEEK_THRESHOLD: 1.0,
  DEFAULT_REWIND_THRESHOLD: 4.0,
  SLOWDOWN_RATE: 0.95,
  DEFAULT_SLOWDOWN_KICKIN_THRESHOLD: 1.5,
  SLOWDOWN_RESET_THRESHOLD: 0.1,
  DIFFERENT_DURATION_THRESHOLD: 2.5,
  PROTOCOL_TIMEOUT: 12.5,
  /** While a device waits for approval the server repeats its status every 60 s; this much silence is a lost connection. */
  PENDING_TIMEOUT: 90.0,
  PING_MOVING_AVERAGE_WEIGHT: 0.85,
  PLAYER_ASK_DELAY_MS: 100,
  PLAYLIST_LOAD_NEXT_FILE_MINIMUM_LENGTH: 10.0,
  PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD: 5.0,
  NEWFILE_IGNORE_TIME: 1.0,
  STREAM_ADDITIONAL_IGNORE_TIME: 10.0,
  RECENT_REWIND_THRESHOLD: 5.0,
  AUTOPLAY_DELAY: 3.0,
} as const;

export enum UnpauseMode {
  /** Unpausing always unpauses the room. */
  ALWAYS = "ALWAYS",
  /** Unpausing only unpauses the room if everyone else is ready; otherwise it just marks you ready. */
  IF_OTHERS_READY = "IF_OTHERS_READY",
  /** First unpause marks you ready, second one unpauses. */
  IF_ALREADY_READY = "IF_ALREADY_READY",
}

/** The desktop client's sync tuning, with the same defaults. */
export interface SyncSettings {
  rewindOnDesync: boolean;
  rewindThreshold: number;
  slowOnDesync: boolean;
  slowdownThreshold: number;
  unpauseMode: UnpauseMode;
  /** Ready state sent right after connecting when we have no previous ready state. */
  readyAtStart: boolean;
  /** "Enable shared playlists": follow the room's playlist selection and tell the room which entry we play. */
  sharedPlaylists: boolean;
  /** "Loop at end of playlist": after the last entry, go back to the first. */
  loopPlaylist: boolean;
  /** "Loop single file": replay the file when it's the playlist's only entry. */
  loopSingleFile: boolean;
}

export const defaultSyncSettings = (): SyncSettings => ({
  rewindOnDesync: true,
  rewindThreshold: Constants.DEFAULT_REWIND_THRESHOLD,
  slowOnDesync: true,
  slowdownThreshold: Constants.DEFAULT_SLOWDOWN_KICKIN_THRESHOLD,
  unpauseMode: UnpauseMode.IF_OTHERS_READY,
  readyAtStart: false,
  sharedPlaylists: true,
  loopPlaylist: false,
  loopSingleFile: false,
});

export interface FileInfo {
  name: string;
  /** Seconds. */
  duration: number;
  /** Bytes; 0 when unknown or not shared. */
  size: number;
  /** Instead of [size], from a Syncplay client in "send hashed" privacy mode: see hashSize. */
  sizeHash?: string | null;
}

/** Seconds; only differences matter. */
export type Clock = () => number;

export const systemClock: Clock = () => Date.now() / 1000;

/**
 * What the sync logic needs from a media player. Reads must reflect commands immediately: after setPaused or seek,
 * isPaused and position return the requested values even if the player applies them asynchronously.
 */
export interface PlayerAdapter {
  readonly isFileLoaded: boolean;
  /** Seconds, 0 when unknown. */
  readonly duration: number;
  /** Seconds. */
  readonly position: number;
  readonly isPaused: boolean;
  /**
   * True when the most recent position jump was initiated by the user (or app skip button), false when the player's
   * demuxer jumped on its own (e.g. skipping over a zero-filled gap in a partial local file). Defaults to true.
   */
  readonly userSeeked?: boolean;
  setPaused(paused: boolean): void;
  seek(position: number): void;
  setSpeed(speed: number): void;
}

/** "mm:ss", or "h:mm:ss" from an hour. */
export function formatTime(seconds: number): string {
  const total = Math.floor(Math.max(seconds, 0));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const two = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${two(m)}:${two(s)}`;
}
