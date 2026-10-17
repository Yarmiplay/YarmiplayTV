import { beforeEach, describe, expect, it } from "vitest";
import { SyncEngine, type SyncHost } from "./engine";
import { Constants, defaultSyncSettings, type PlayerAdapter } from "./models";

// Ported from SyncEngineTest.kt and its Fakes.kt.

class FakeClock {
  time = 1000;
  now = () => this.time;
  advance(seconds: number) {
    this.time += seconds;
  }
}

/** A player whose position advances with the clock while playing. */
class FakePlayer implements PlayerAdapter {
  isFileLoaded = true;
  speed = 1;
  readonly seeks: number[] = [];
  private basePosition = 0;
  private baseTime: number;
  private paused = true;

  constructor(private readonly clock: FakeClock, public duration = 1440) {
    this.baseTime = clock.now();
  }

  get position() {
    return this.paused ? this.basePosition : this.basePosition + (this.clock.now() - this.baseTime) * this.speed;
  }
  get isPaused() {
    return this.paused;
  }
  setPaused(paused: boolean) {
    this.rebase();
    this.paused = paused;
  }
  seek(position: number) {
    this.seeks.push(position);
    this.basePosition = position;
    this.baseTime = this.clock.now();
  }
  setSpeed(speed: number) {
    this.rebase();
    this.speed = speed;
  }
  private rebase() {
    this.basePosition = this.position;
    this.baseTime = this.clock.now();
  }
}

interface Sent {
  position: number | null;
  paused: boolean | null;
  doSeek: boolean;
  stateChange: boolean;
}

class RecordingHost implements SyncHost {
  username = "tv";
  readinessSupported = true;
  isReady: boolean | null = false;
  currentFileDuration = 1440;
  othersReady = true;
  controlsPlayback = true;
  lastPlaylistChangeAge = 100;
  readonly sent: Sent[] = [];
  readonly readyChanges: [boolean, boolean][] = [];
  readonly notifications: string[] = [];
  advanceChecks = 0;

  areAllOtherUsersReady = () => this.othersReady;
  notJustChangedPlaylist = () => this.lastPlaylistChangeAge > Constants.PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD;
  sendState(position: number | null, paused: boolean | null, doSeek: boolean, _latency: number | null, stateChange: boolean) {
    this.sent.push({ position, paused, doSeek, stateChange });
  }
  changeReadyState(ready: boolean, manuallyInitiated: boolean) {
    this.isReady = ready;
    this.readyChanges.push([ready, manuallyInitiated]);
  }
  notify(message: string) {
    this.notifications.push(message);
  }
  advancePlaylistCheck() {
    this.advanceChecks++;
  }
}

describe("SyncEngine", () => {
  let clock: FakeClock;
  let player: FakePlayer;
  let host: RecordingHost;
  let engine: SyncEngine;

  beforeEach(() => {
    clock = new FakeClock();
    player = new FakePlayer(clock);
    host = new RecordingHost();
    engine = new SyncEngine(player, clock.now, defaultSyncSettings(), host);
  });

  /** Server says the room is playing at [position]; engine polls the player afterwards like the real client. */
  function serverState(position: number, paused: boolean, doSeek = false, setBy: string | null = "alice") {
    engine.updateGlobalState(position, paused, doSeek, setBy, 0);
    engine.poll();
  }

  function step(seconds: number) {
    for (let i = 0; i < Math.trunc(seconds * 10 + 1e-9); i++) {
      clock.advance(0.1);
      engine.poll();
    }
  }

  /** The room switches to a new file, paused: it opens for [opening] seconds, then loads at the start. */
  function switchToNewFile(opening: number, whileOpening: () => void = () => {}) {
    serverState(900, true);
    step(1);
    player.isFileLoaded = false;
    engine.onFileLoading();
    whileOpening();
    step(opening);
    player.isFileLoaded = true;
    engine.onFileLoaded(true);
  }

  it("first state moves the player to the room position and play state", () => {
    serverState(120, false);
    expect(player.position).toBeCloseTo(120, 2);
    expect(player.isPaused).toBe(false);
  });

  it("remote pause pauses the player without echoing a state change", () => {
    serverState(10, false);
    step(2);
    host.sent.length = 0;
    serverState(12, true, false, "alice");
    step(0.5);
    expect(player.isPaused).toBe(true);
    expect(host.sent.filter((s) => s.stateChange)).toEqual([]);
    expect(host.notifications.some((n) => n.startsWith("alice paused"))).toBe(true);
  });

  it("remote seek jumps the player", () => {
    serverState(10, false);
    step(1);
    serverState(600, false, true, "alice");
    expect(Math.abs(player.position - 600)).toBeLessThan(0.2);
    expect(host.notifications).toContain("alice jumped from 00:11 to 10:00");
    step(1);
    expect(host.sent.some((s) => s.doSeek)).toBe(false);
  });

  it("a remote seek to where we already are is not announced", () => {
    serverState(0, true);
    step(1);
    serverState(0, true, true, "alice");
    expect(host.notifications.some((n) => n.includes("jumped"))).toBe(false);
  });

  it("a non-operator's local pause and seek are put back, not sent, and readiness stays", () => {
    host.controlsPlayback = false;
    host.isReady = true;
    serverState(10, false);
    step(1);
    host.sent.length = 0;

    player.setPaused(true);
    step(0.2);
    expect(player.isPaused).toBe(false);
    player.seek(300);
    step(0.2);
    expect(Math.abs(player.position - 11.4)).toBeLessThan(0.5);
    step(1);
    expect(host.sent.some((s) => s.stateChange || s.doSeek)).toBe(false);
    expect(host.readyChanges).toEqual([]);
    expect(host.notifications.some((n) => n.startsWith("You jumped"))).toBe(false);
    const notices = () => host.notifications.filter((n) => n === "Only room operators can control playback").length;
    expect(notices()).toBe(1);

    // Again after 10 s.
    step(10);
    player.setPaused(true);
    step(0.2);
    expect(notices()).toBe(2);
  });

  it("a non-operator still follows the operators' pause and seek", () => {
    host.controlsPlayback = false;
    serverState(10, false);
    step(1);
    serverState(600, true, true, "op");
    expect(player.isPaused).toBe(true);
    expect(Math.abs(player.position - 600)).toBeLessThan(0.2);
    expect(host.notifications).not.toContain("Only room operators can control playback");
  });

  it("an operator's local pause and seek are sent as before", () => {
    serverState(10, false);
    step(1);
    player.seek(300);
    step(0.2);
    expect(host.sent.at(-1)!.doSeek).toBe(true);
    expect(host.notifications).not.toContain("Only room operators can control playback");
  });

  it("local seek is reported with doSeek", () => {
    serverState(10, false);
    step(1);
    player.seek(300);
    step(0.2);
    const seek = host.sent.at(-1)!;
    expect(seek.doSeek).toBe(true);
    expect(seek.stateChange).toBe(true);
    expect(Math.abs(seek.position! - 300)).toBeLessThan(0.5);
  });

  it("being more than the rewind threshold ahead rewinds to the room", () => {
    serverState(100, false);
    step(1);
    // Someone else in the room is 5 seconds behind us.
    const behind = player.position - 5;
    engine.updateGlobalState(behind, false, false, "alice", 0);
    expect(Math.abs(player.position - behind)).toBeLessThan(0.2);
    expect(host.notifications.some((n) => n.startsWith("Rewinded"))).toBe(true);
  });

  it("small drift slows playback and recovers", () => {
    serverState(100, false);
    step(1);
    engine.updateGlobalState(player.position - 2, false, false, "alice", 0);
    expect(player.speed).toBeCloseTo(Constants.SLOWDOWN_RATE, 4);
    engine.updateGlobalState(player.position, false, false, "alice", 0);
    expect(player.speed).toBeCloseTo(1, 4);
  });

  it("local pause marks not ready and is sent to the room", () => {
    host.isReady = true;
    serverState(50, false);
    step(1);
    host.sent.length = 0;
    player.setPaused(true);
    step(0.2);
    expect(host.readyChanges.at(-1)).toEqual([false, false]);
    expect(host.sent.some((s) => s.paused === true && s.stateChange)).toBe(true);
  });

  it("unpausing while others are not ready only marks us ready", () => {
    host.othersReady = false;
    host.isReady = false;
    serverState(50, true);
    step(0.5);
    player.setPaused(false);
    step(0.2);
    expect(player.isPaused).toBe(true);
    expect(host.readyChanges.at(-1)).toEqual([true, true]);
    expect(host.sent.some((s) => s.paused === false && s.stateChange)).toBe(false);
  });

  it("unpausing when everyone else is ready unpauses the room", () => {
    host.othersReady = true;
    serverState(50, true);
    step(0.5);
    player.setPaused(false);
    step(0.2);
    expect(player.isPaused).toBe(false);
    expect(host.sent.some((s) => s.paused === false && s.stateChange)).toBe(true);
  });

  it("pausing at the end of the file asks to advance the playlist", () => {
    serverState(1437, false);
    step(1);
    player.setPaused(true);
    step(0.2);
    expect(host.advanceChecks).toBe(1);
  });

  it("reset file load reports position zero and ignores stale room position", () => {
    serverState(900, false);
    step(1);
    engine.onFileLoaded(true);
    expect(player.position).toBeCloseTo(0, 2);
    // Old room position arrives before others have switched: must not jump back to 900.
    engine.updateGlobalState(901, false, false, "alice", 0);
    expect(player.position).toBeLessThan(5);
  });

  it("play pressed a moment after a switched file appears plays it", () => {
    host.othersReady = true;
    switchToNewFile(3);
    step(1);
    player.setPaused(false);
    step(1);
    expect(player.isPaused).toBe(false);
    expect(host.sent.some((s) => s.paused === false && s.stateChange)).toBe(true);
  });

  it("play pressed while a streamed file is still opening plays it once it has loaded", () => {
    host.othersReady = true;
    switchToNewFile(3, () => player.setPaused(false));
    step(1.5);
    expect(player.isPaused).toBe(false);
    expect(player.position).toBeLessThan(2);
    expect(host.sent.some((s) => s.paused === false && s.stateChange)).toBe(true);
  });

  it("play pressed while opening, with others not ready, marks us ready", () => {
    host.othersReady = false;
    host.isReady = false;
    switchToNewFile(3, () => player.setPaused(false));
    step(1.5);
    expect(player.isPaused).toBe(true);
    expect(host.readyChanges.at(-1)).toEqual([true, true]);
    expect(host.sent.some((s) => s.paused === false && s.stateChange)).toBe(false);
  });

  it("a switched file that wasn't played while opening stays paused", () => {
    host.othersReady = true;
    switchToNewFile(3);
    step(3);
    expect(player.isPaused).toBe(true);
    expect(host.sent.some((s) => s.paused === false && s.stateChange)).toBe(false);
  });

  it("non-reset file load jumps to the room position", () => {
    player.isFileLoaded = false;
    serverState(300, false);
    step(1);
    player.isFileLoaded = true;
    engine.onFileLoaded(false);
    expect(Math.abs(player.position - 301)).toBeLessThan(0.3);
    expect(player.isPaused).toBe(false);
  });

  it("joining mid-playback does not pause or rewind the room while the file opens", () => {
    player.isFileLoaded = false;
    engine.onFileLoading();
    serverState(444, false);
    step(1);
    // The player has opened the file (paused at 0) but the app hasn't reported it yet.
    player.isFileLoaded = true;
    step(1);
    expect(host.sent.some((s) => s.stateChange)).toBe(false);
    expect(host.readyChanges).toEqual([]);
    engine.onFileLoaded(false);
    step(1);
    expect(Math.abs(player.position - 447)).toBeLessThan(0.3);
    expect(player.isPaused).toBe(false);
    expect(host.sent.some((s) => s.stateChange)).toBe(false);
  });

  it("no file loaded never sends a state change", () => {
    player.isFileLoaded = false;
    serverState(10, false);
    step(3);
    serverState(13, true);
    step(1);
    expect(host.sent.some((s) => s.stateChange)).toBe(false);
  });

  it("uninitiated player jump from a partial file does not seek the room or report jumped position", () => {
    serverState(42, false);
    step(1);
    host.sent.length = 0;
    host.notifications.length = 0;

    (player as FakePlayer & { userSeeked?: boolean }).userSeeked = false;
    player.seek(48);
    step(0.2);

    expect(host.sent.some((s) => s.doSeek)).toBe(false);
    expect(host.notifications.some((n) => n.startsWith("You jumped"))).toBe(false);
    expect(Math.abs(engine.getPlayerPosition() - engine.getGlobalPosition())).toBeLessThan(0.3);
    expect(engine.getLocalState()?.doSeek).toBe(false);
  });
});
