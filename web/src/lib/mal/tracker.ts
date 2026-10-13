import type { EpisodeInfo } from "./episode";

/** How far through an episode counts as watched. */
export const WATCHED_FRACTION = 0.9;

/** What the room (or this device alone) is playing. */
export interface TrackedFile {
  /** Identifies the file (its URL or name); a different key is a different file. */
  key: string;
  /** Null when it isn't an episode of a show. */
  episode: EpisodeInfo | null;
  /** The length the playlist knows, for while the player doesn't yet. */
  durationHint?: number;
}

/** The player as the tracker needs it. */
export interface TrackedPlayback {
  /** The file the player has open (the [TrackedFile.key] it was given); null for none. */
  key: string | null;
  fileLoaded: boolean;
  duration: number;
  position: number;
  ended: boolean;
}

/**
 * Tells [MalService] what's playing and when it has been watched: [WATCHED_FRACTION] of the way through (or to
 * the end), once per file. It only watches the player: call [update] whenever what's playing or the player's
 * position changes.
 */
export class MalTracker {
  private key: string | null = null;
  private reported = false;

  constructor(private readonly service: { playing(info: EpisodeInfo | null): void; watched(info: EpisodeInfo): unknown }) {}

  update(file: TrackedFile | null, player: TrackedPlayback) {
    if ((file?.key ?? null) !== this.key) {
      this.key = file?.key ?? null;
      this.reported = false;
      this.service.playing(file?.episode ?? null);
    }
    const info = file?.episode;
    if (!file || !info || this.reported || player.key !== file.key || !player.fileLoaded) return;
    const duration = player.duration > 0 ? player.duration : (file.durationHint ?? 0);
    if (duration > 0 && (player.ended || player.position >= duration * WATCHED_FRACTION)) {
      this.reported = true;
      void this.service.watched(info);
    }
  }
}
