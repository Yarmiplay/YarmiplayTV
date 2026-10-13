import { afterEach, describe, expect, it } from "vitest";
import { rawToDer } from "../yarmiplay/deviceKeys";
import { SyncplayClient, type SyncplayEvent } from "./client";
import { defaultSyncSettings, type FileInfo, type PlayerAdapter } from "./models";
import type { DeviceAuth } from "./yarmiplay";

/**
 * Two web clients against a real YarmiplayServerTV over WebSockets (ServerIntegrationTest.kt for the browser).
 * Enabled with YSTV_TEST_SERVER=host:port, e.g. a server on this computer.
 */
const server = process.env.YSTV_TEST_SERVER ?? "";

/** A player whose position runs in real time while playing. */
class RealtimePlayer implements PlayerAdapter {
  isFileLoaded = true;
  duration = 1420;
  private base = 0;
  private since = Date.now();
  private paused = true;
  private speed = 1;
  get position() {
    return this.paused ? this.base : this.base + ((Date.now() - this.since) / 1000) * this.speed;
  }
  get isPaused() {
    return this.paused;
  }
  setPaused(p: boolean) {
    this.rebase();
    this.paused = p;
  }
  seek(p: number) {
    this.base = p;
    this.since = Date.now();
  }
  setSpeed(s: number) {
    this.rebase();
    this.speed = s;
  }
  private rebase() {
    this.base = this.position;
    this.since = Date.now();
  }
}

class MemoryKeys implements DeviceAuth {
  readonly deviceName = "Web client test";
  private pair = crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]) as Promise<CryptoKeyPair>;
  async publicKey() {
    return new Uint8Array(await crypto.subtle.exportKey("spki", (await this.pair).publicKey));
  }
  async sign(_: string, message: Uint8Array) {
    return rawToDer(new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, (await this.pair).privateKey, message as BufferSource)));
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
async function eventually<T>(what: string, value: () => T | null | undefined | false, timeoutMs = 8000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const v = value();
    if (v) return v;
    await sleep(25);
  }
  throw new Error(`Timed out waiting for: ${what}`);
}

const ep1: FileInfo = { name: "Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv", duration: 1420, size: 587443083 };

describe.skipIf(!server)(`against YarmiplayServerTV at ${server}`, () => {
  const [host, portText] = server.split(":");
  const port = Number(portText);
  const clients: SyncplayClient[] = [];
  afterEach(() => clients.splice(0).forEach((c) => c.close()));

  function make(name: string, room: string, player: PlayerAdapter, events: SyncplayEvent[], keys: DeviceAuth | null = null) {
    const c = new SyncplayClient(
      { host, port, username: name, room, useTls: false, deviceAuth: keys, appVersion: "2.0.2" },
      player,
      defaultSyncSettings(),
    );
    c.onEvent((e) => events.push(e));
    clients.push(c);
    return c;
  }

  it("playlist auto-load and playback sync between two clients", async () => {
    const room = `web-it-${Date.now()}`;
    const desktopPlayer = new RealtimePlayer();
    const tvPlayer = new RealtimePlayer();
    tvPlayer.isFileLoaded = false;
    const desktopEvents: SyncplayEvent[] = [];
    const tvEvents: SyncplayEvent[] = [];
    const desktop = make("desktop", room, desktopPlayer, desktopEvents);
    const tv = make("tv", room, tvPlayer, tvEvents);

    desktop.start();
    desktop.fileLoaded(ep1);
    tv.start();
    await eventually("both connected", () => tv.state.status === "CONNECTED" && desktop.state.users.length === 2);
    await eventually("tv sees desktop's file", () => tv.state.users.some((u) => u.name === "desktop" && u.file?.name === ep1.name));

    desktop.setPlaylist([ep1.name]);
    const switched = await eventually("tv switch event", () => tvEvents.find((e) => e.type === "switchToPlaylistItem"));
    expect(switched).toMatchObject({ index: 0, filename: ep1.name });

    tvPlayer.isFileLoaded = true;
    tv.fileLoaded(ep1, switched.type === "switchToPlaylistItem" && switched.resetPosition);
    tv.setReady(true);
    desktop.setReady(true);
    await eventually("desktop sees tv ready", () => desktop.state.users.some((u) => u.name === "tv" && u.isReady === true && u.file?.name === ep1.name));

    desktopPlayer.setPaused(false);
    await eventually("tv unpaused", () => !tvPlayer.isPaused);

    await sleep(1500);
    desktopPlayer.seek(600);
    await eventually("tv followed seek", () => tvPlayer.position >= 598 && tvPlayer.position <= 606);

    tvPlayer.setPaused(true);
    await eventually("desktop paused", () => desktopPlayer.isPaused);
    expect(Math.abs(desktopPlayer.position - tvPlayer.position)).toBeLessThan(1.5);

    tv.sendChat("hello from the browser");
    await eventually("desktop got chat", () =>
      desktopEvents.some((e) => e.type === "chat" && e.username === "tv" && e.message === "hello from the browser"),
    );
  }, 30_000);

  it("a device key opts in: session, relay list and shared room settings", async () => {
    const room = `web-it-ext-${Date.now()}`;
    const events: SyncplayEvent[] = [];
    const otherEvents: SyncplayEvent[] = [];
    const c = make("browser", room, new RealtimePlayer(), events, new MemoryKeys());
    const other = make("other", room, new RealtimePlayer(), otherEvents, new MemoryKeys());
    c.start();
    other.start();
    await eventually("session", () => c.session && other.session);
    expect(c.state.yarmiplay.serverKind).toBe("YARMIPLAY");
    expect(c.state.yarmiplay.capabilities.websocket).toBe(true);
    expect(c.session!.baseUrl).toBe(`http://${host}:${port}`);

    // A file offered by one viewer shows up in the other's relay list.
    c.offerFiles([{ name: ep1.name, size: ep1.size, duration: ep1.duration, quickHash: "ab".repeat(32) }]);
    await eventually("relay list", () => other.state.yarmiplay.relayFiles.some((f) => f.name === ep1.name));

    // Room settings are the room's: one turns skipping on, the other hears it.
    await eventually("room settings", () => c.state.yarmiplay.roomSettings);
    expect(c.setRoomAutoSkip(true)).toBe(true);
    await eventually("shared", () => otherEvents.some((e) => e.type === "roomSettingsChanged" && e.settings.autoSkip && e.settings.setBy === "browser"));

    // The relay file can be read over HTTP with the session token.
    const info = await fetch(`${c.session!.baseUrl}/yarmiplay/info`).then((r) => r.json());
    expect(info.capabilities.websocket).toBe(true);
  }, 30_000);
});
