import { Constants, UnpauseMode, formatTime, type Clock, type PlayerAdapter, type SyncSettings } from "./models";

export interface SyncHost {
  readonly username: string;
  readonly readinessSupported: boolean;
  /** False in a managed room we're not an operator of: the room's operators control playback. */
  readonly controlsPlayback: boolean;
  readonly isReady: boolean | null;
  /** Duration of the file we reported to the server, 0 when none. */
  readonly currentFileDuration: number;
  areAllOtherUsersReady(): boolean;
  notJustChangedPlaylist(): boolean;
  sendState(position: number | null, paused: boolean | null, doSeek: boolean, latencyCalculation: number | null, stateChange: boolean): void;
  changeReadyState(ready: boolean, manuallyInitiated: boolean): void;
  notify(message: string): void;
  advancePlaylistCheck(): void;
}

export interface LocalState {
  position: number;
  paused: boolean;
  doSeek: boolean;
  stateChange: boolean;
}

/** Seconds between "Only room operators can control playback" notices. */
const BLOCKED_NOTICE_INTERVAL = 10.0;
/** Seconds a rewind's guard lasts once the file has loaded after it (Syncplay's 5 s less 4.5). */
const REWIND_GUARD_AFTER_LOAD = 0.5;

/**
 * Keeps the local player in step with the room: a port of SyncEngine.kt, itself a port of the playback-state half of
 * Syncplay's client.py (updatePlayerStatus, updateGlobalState, getLocalState and helpers), so it behaves as desktop
 * Syncplay users are used to.
 */
export class SyncEngine {
  private globalPosition = 0;
  private globalPaused = true;
  private lastGlobalUpdate: number | null = null;

  private playerPosition = 0;
  private playerPaused = true;
  private lastPlayerUpdate: number | null = null;

  private speedChanged = false;
  private lastRewindTime: number | null = null;
  /** When the last file finished loading; after it, a rewind's guard is short (Syncplay's lastUpdatedFileTime). */
  private lastFileLoadTime: number | null = null;
  /** Play was pressed while the file was opening: it's pressed for the user once the new file has settled. */
  private unpauseAt: number | null = null;
  private lastAdvanceTime: number | null = null;
  private lastBlockedNotice: number | null = null;
  /** After a reset-to-start file switch the player reports position 0 until this time, like Syncplay's mpv wrapper. */
  private resetIgnoreUntil: number | null = null;
  /**
   * False between onFileLoading and onFileLoaded. The player already reports the new file (paused at 0) before the
   * room knows about it, which would otherwise look like a local pause + seek.
   */
  private fileReady = true;

  constructor(
    private readonly player: PlayerAdapter,
    private readonly clock: Clock,
    public settings: SyncSettings,
    private readonly host: SyncHost,
  ) {}

  private get playerReady(): boolean {
    return this.fileReady && this.player.isFileLoaded;
  }

  get hasGlobalState(): boolean {
    return this.lastGlobalUpdate !== null;
  }

  onDisconnected(): void {
    this.unpauseAt = null;
    this.lastGlobalUpdate = null;
    this.lastPlayerUpdate = null;
    if (this.speedChanged) {
      this.player.setSpeed(1.0);
      this.speedChanged = false;
    }
  }

  // --- Player side ---------------------------------------------------------

  private readPlayerPosition(): number {
    const now = this.clock();
    if (this.resetIgnoreUntil !== null) {
      if (now < this.resetIgnoreUntil) return 0;
      this.resetIgnoreUntil = null;
    }
    return this.playerReady ? Math.max(this.player.position, 0) : this.getGlobalPosition();
  }

  private readPlayerPaused(): boolean {
    return this.playerReady ? this.player.isPaused : this.getGlobalPaused();
  }

  /** Call every PLAYER_ASK_DELAY_MS while connected. */
  poll(): void {
    if (this.unpauseAt !== null && this.clock() >= this.unpauseAt) {
      this.unpauseAt = null;
      // As if pressed now: the next lines see a local unpause, which plays or marks us ready as usual.
      if (this.playerReady && this.player.isPaused) this.player.setPaused(false);
    }
    this.updatePlayerStatus(this.readPlayerPaused(), this.readPlayerPosition());
  }

  private determinePlayerStateChange(paused: boolean, position: number): [boolean, boolean] {
    const pauseChange = this.getPlayerPaused() !== paused && this.getGlobalPaused() !== paused;
    const playerDiff = Math.abs(this.getPlayerPosition() - position);
    const globalDiff = Math.abs(this.getGlobalPosition() - position);
    const seeked = playerDiff > Constants.SEEK_THRESHOLD && globalDiff > Constants.SEEK_THRESHOLD;
    return [pauseChange, seeked];
  }

  private updatePlayerStatus(paused: boolean, position: number): void {
    let [pauseChange, seeked] = this.determinePlayerStateChange(paused, position);
    if ((pauseChange || seeked) && this.lastGlobalUpdate !== null && !this.host.controlsPlayback) {
      this.followOperators(pauseChange, seeked);
      return;
    }
    this.playerPosition = position;
    this.playerPaused = paused;
    const length = this.host.currentFileDuration;
    const nearEnd = Math.abs(position - length) < Constants.PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD;
    if (pauseChange && paused && length > Constants.PLAYLIST_LOAD_NEXT_FILE_MINIMUM_LENGTH && nearEnd) {
      this.host.advancePlaylistCheck();
    } else if (pauseChange && this.host.readinessSupported) {
      if (length <= 0 || !(!this.host.notJustChangedPlaylist() && nearEnd)) {
        pauseChange = this.toggleReady(pauseChange, paused);
      }
    }
    if (this.lastGlobalUpdate !== null) {
      this.lastPlayerUpdate = this.clock();
      if (pauseChange || seeked) {
        if (this.recentlyRewound() || this.recentlyAdvanced()) {
          this.host.sendState(this.globalPosition, this.getPlayerPaused(), false, null, true);
          return;
        }
        if (seeked) this.host.notify(`You jumped to ${formatTime(position)}`);
        this.host.sendState(this.getPlayerPosition(), this.getPlayerPaused(), seeked, null, true);
      }
    }
  }

  /**
   * In a managed room we're not an operator of: a local pause, unpause or seek isn't sent (the server would put it
   * back anyway). The player goes straight back to the room's state, without touching readiness.
   */
  private followOperators(pauseChange: boolean, seeked: boolean): void {
    const position = this.getGlobalPosition();
    const paused = this.getGlobalPaused();
    if (seeked) this.setPosition(position);
    if (pauseChange) this.setPlayerPaused(paused);
    this.playerPosition = position;
    this.playerPaused = paused;
    this.lastPlayerUpdate = this.clock();
    const now = this.clock();
    if (this.lastBlockedNotice === null || now - this.lastBlockedNotice >= BLOCKED_NOTICE_INTERVAL) {
      this.lastBlockedNotice = now;
      this.host.notify("Only room operators can control playback");
    }
  }

  private toggleReady(pauseChange: boolean, paused: boolean): boolean {
    if (this.recentlyRewound() && this.globalPaused && !this.recentlyAdvanced()) {
      this.setPlayerPaused(this.globalPaused);
      this.playerPaused = this.globalPaused;
      return false;
    }
    if (!paused && !this.instaplayConditionsMet()) {
      this.setPlayerPaused(true);
      this.playerPaused = true;
      this.host.changeReadyState(true, true);
      this.host.notify("You are ready. Waiting for the others; press play again to start anyway.");
      return false;
    }
    this.host.changeReadyState(!this.getPlayerPaused(), false);
    return pauseChange;
  }

  private instaplayConditionsMet(): boolean {
    if (this.host.isReady === true || this.settings.unpauseMode === UnpauseMode.ALWAYS) return true;
    return this.settings.unpauseMode === UnpauseMode.IF_OTHERS_READY && this.host.areAllOtherUsersReady();
  }

  /** Reply payload for a server State message. Null until the first server State arrives. */
  getLocalState(): LocalState | null {
    if (this.lastGlobalUpdate === null) return null;
    const paused = this.getPlayerPaused();
    const position = this.getPlayerPosition();
    const [pauseChange, seeked] = this.determinePlayerStateChange(paused, position);
    return { position, paused, doSeek: seeked, stateChange: pauseChange };
  }

  // --- Room side ------------------------------------------------------------

  updateGlobalState(position: number, paused: boolean, doSeek: boolean, setBy: string | null, messageAge: number): void {
    const adjusted = paused ? position : position + messageAge;
    const madeChangeOnPlayer = this.changePlayerStateAccordingToGlobalState(adjusted, paused, doSeek, setBy);
    // Re-read the player straight away (Syncplay's askPlayer) so the reply to this State carries the state we just
    // applied instead of the stale one from the last poll.
    if (madeChangeOnPlayer) this.poll();
  }

  private changePlayerStateAccordingToGlobalState(position: number, paused: boolean, doSeek: boolean, setBy: string | null): boolean {
    let madeChange = false;
    const pauseChanged = paused !== this.getGlobalPaused() || paused !== this.getPlayerPaused();
    const diff = this.getPlayerPosition() - position;
    if (this.lastGlobalUpdate === null) madeChange = this.initPlayerState(position, paused);
    this.globalPaused = paused;
    this.globalPosition = position;
    this.lastGlobalUpdate = this.clock();
    if (doSeek) madeChange = this.serverSeeked(position, setBy) || madeChange;
    if (diff > this.settings.rewindThreshold && !doSeek && this.settings.rewindOnDesync) {
      madeChange = this.rewindDueToTimeDifference(position, setBy) || madeChange;
    }
    if (!doSeek && !paused && this.settings.slowOnDesync) madeChange = this.slowDownToCoverTimeDifference(diff, setBy) || madeChange;
    if (!paused && pauseChanged) madeChange = this.serverUnpaused(setBy) || madeChange;
    else if (paused && pauseChanged) madeChange = this.serverPaused(setBy) || madeChange;
    return madeChange;
  }

  private initPlayerState(position: number, paused: boolean): boolean {
    if (!this.playerReady) return false;
    this.setPosition(position);
    this.setPlayerPaused(paused);
    return true;
  }

  private serverSeeked(position: number, setBy: string | null): boolean {
    if (this.host.username === setBy) return false;
    const before = this.getPlayerPosition();
    this.setPosition(position);
    // A new file starting at 0 also arrives as a seek; "jumped from 00:00 to 00:00" would only be noise.
    if (Math.abs(before - position) > Constants.SEEK_THRESHOLD) {
      this.host.notify(`${setBy ?? "Someone"} jumped from ${formatTime(before)} to ${formatTime(position)}`);
    }
    return true;
  }

  private rewindDueToTimeDifference(position: number, setBy: string | null): boolean {
    if (this.host.username === setBy) return false;
    this.setPosition(position);
    this.host.notify(`Rewinded due to time difference with ${setBy ?? "the room"}`);
    return true;
  }

  private slowDownToCoverTimeDifference(diff: number, setBy: string | null): boolean {
    if (this.settings.slowdownThreshold < diff && !this.speedChanged) {
      if (this.host.username === setBy) return false;
      this.player.setSpeed(Constants.SLOWDOWN_RATE);
      this.speedChanged = true;
      return true;
    } else if (this.speedChanged && diff < Constants.SLOWDOWN_RESET_THRESHOLD) {
      this.player.setSpeed(1.0);
      this.speedChanged = false;
      return true;
    }
    return false;
  }

  private serverUnpaused(setBy: string | null): boolean {
    this.setPlayerPaused(false);
    if (setBy !== null && setBy !== this.host.username) this.host.notify(`${setBy} unpaused`);
    return true;
  }

  private serverPaused(setBy: string | null): boolean {
    if (this.host.username !== setBy) this.setPosition(this.getGlobalPosition());
    this.setPlayerPaused(true);
    if (setBy !== null && setBy !== this.host.username) {
      this.host.notify(`${setBy} paused at ${formatTime(this.getGlobalPosition())}`);
    }
    return true;
  }

  // --- File switching -------------------------------------------------------

  /**
   * Call once the player has loaded a new file. With [resetPosition] playback starts from 0 (a playlist switch);
   * otherwise it jumps to the room's position.
   */
  onFileLoaded(resetPosition: boolean): void {
    this.fileReady = true;
    const now = this.clock();
    // The file opens paused, so playing now means play was pressed while it opened (a slow stream).
    const playPressed = this.player.isFileLoaded && !this.player.isPaused;
    this.lastFileLoadTime = now;
    if (resetPosition) {
      this.resetIgnoreUntil = now + Constants.NEWFILE_IGNORE_TIME;
      this.player.seek(0);
      this.lastRewindTime = now;
      this.playerPosition = 0;
      if (this.lastGlobalUpdate !== null) this.setPlayerPaused(this.globalPaused);
    } else if (this.lastGlobalUpdate !== null) {
      this.player.seek(this.getGlobalPosition());
      this.setPlayerPaused(this.getGlobalPaused());
    }
    if (this.lastPlayerUpdate !== null) this.lastPlayerUpdate = now;
    // The room's pause wins for now; the press counts once the switch can no longer be mistaken for it.
    this.unpauseAt =
      playPressed && this.lastGlobalUpdate !== null && this.getGlobalPaused() ? now + REWIND_GUARD_AFTER_LOAD + 0.1 : null;
  }

  /** Call when the player starts opening a new file; it's ignored until onFileLoaded. */
  onFileLoading(): void {
    this.fileReady = false;
    this.unpauseAt = null;
  }

  markAdvanced(): void {
    this.lastAdvanceTime = this.clock();
  }

  // --- Helpers ----------------------------------------------------------------

  private setPosition(position: number): void {
    const now = this.clock();
    if (this.lastPlayerUpdate !== null) this.lastPlayerUpdate = now;
    const rewound = this.lastRewindTime;
    if (rewound !== null && Math.abs(now - rewound) < 1.0 && position > 5) return;
    if (this.playerReady) this.player.seek(Math.max(position, 0));
  }

  private setPlayerPaused(paused: boolean): void {
    if (!this.playerReady) return;
    if (this.lastPlayerUpdate !== null && !paused) this.lastPlayerUpdate = this.clock();
    if (paused && this.speedChanged) {
      this.player.setSpeed(1.0);
      this.speedChanged = false;
    }
    this.player.setPaused(paused);
  }

  /**
   * Just after a rewind, a local pause or play is taken for the player settling, not the user. As in Syncplay, once
   * the file has loaded after the rewind this lasts only REWIND_GUARD_AFTER_LOAD, else a play pressed as soon as a
   * switched file appears would be undone.
   */
  private recentlyRewound(): boolean {
    const rewound = this.lastRewindTime;
    if (rewound === null) return false;
    const loaded = this.lastFileLoadTime;
    const t =
      loaded !== null && loaded >= rewound ? rewound - (Constants.RECENT_REWIND_THRESHOLD - REWIND_GUARD_AFTER_LOAD) : rewound;
    return Math.abs(this.clock() - t) < Constants.RECENT_REWIND_THRESHOLD;
  }

  private recentlyAdvanced(): boolean {
    const t = this.lastAdvanceTime;
    if (t === null) return false;
    return this.clock() - t < Constants.AUTOPLAY_DELAY + 5;
  }

  getPlayerPosition(): number {
    const lpu = this.lastPlayerUpdate;
    if (lpu === null) return this.lastGlobalUpdate !== null ? this.getGlobalPosition() : 0;
    let position = this.playerPosition;
    if (!this.playerPaused) position += this.clock() - lpu;
    return position;
  }

  getPlayerPaused(): boolean {
    if (this.lastPlayerUpdate === null) return this.lastGlobalUpdate !== null ? this.getGlobalPaused() : true;
    return this.playerPaused;
  }

  getGlobalPosition(): number {
    const lgu = this.lastGlobalUpdate;
    if (lgu === null) return 0;
    let position = this.globalPosition;
    if (!this.globalPaused) position += this.clock() - lgu;
    return position;
  }

  getGlobalPaused(): boolean {
    return this.lastGlobalUpdate === null ? true : this.globalPaused;
  }
}
