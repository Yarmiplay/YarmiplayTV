import { afterEach, describe, expect, it } from "vitest";
import { rawToDer } from "../yarmiplay/deviceKeys";
import { nextPlaylistIndex, SyncplayClient, validIndexFromNewPlaylist, redact, type SyncplayEvent } from "./client";
import type { PlayerAdapter } from "./models";
import type { Transport, TransportFactory } from "./transport";
import { fingerprint, isValidServerId, quickHash, signedMessage, Yarmiplay, type DeviceAuth, type RelayOffer } from "./yarmiplay";

// Ported from YarmiplayClientTest.kt, ManagedRoomsTest.kt and PlaylistLogicTest.kt: the client against a scripted
// server, with everything it sends recorded.

/** A connection whose server lines come from the test. */
class ScriptedTransport implements Transport {
  readonly tls = false;
  readonly host = "yarmi.example";
  readonly sent: string[] = [];
  closed = false;
  onLine: (line: string) => void = () => {};
  onClose: (reason: string) => void = () => {};

  send(line: string) {
    if (!this.closed) this.sent.push(line);
  }
  close() {
    if (this.closed) return;
    this.closed = true;
    queueMicrotask(() => this.onClose("closed"));
  }
  receive(line: string) {
    this.onLine(line);
  }
  sentJson(): Record<string, any>[] {
    return this.sent.map((l) => JSON.parse(l));
  }
}

class StillPlayer implements PlayerAdapter {
  isFileLoaded = true;
  duration = 1440;
  position = 0;
  isPaused = true;
  setPaused(p: boolean) {
    this.isPaused = p;
  }
  seek(p: number) {
    this.position = p;
  }
  setSpeed() {}
}

/** P-256 keys in memory, one per server, signing as the browser's device keys do. */
class TestDeviceAuth implements DeviceAuth {
  readonly deviceName = "Living room TV";
  readonly pairs = new Map<string, Promise<CryptoKeyPair>>();
  private pair(serverId: string) {
    if (!this.pairs.has(serverId)) {
      this.pairs.set(serverId, crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]) as Promise<CryptoKeyPair>);
    }
    return this.pairs.get(serverId)!;
  }
  async publicKey(serverId: string) {
    return new Uint8Array(await crypto.subtle.exportKey("spki", (await this.pair(serverId)).publicKey));
  }
  async sign(serverId: string, message: Uint8Array) {
    const raw = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, (await this.pair(serverId)).privateKey, message as BufferSource);
    return rawToDer(new Uint8Array(raw));
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function eventually<T>(what: string, value: () => T | null | undefined | false, timeoutMs = 4000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const v = value();
    if (v) return v;
    await sleep(10);
  }
  throw new Error(`Timed out waiting for: ${what}`);
}

const serverId = "9b1f0c2e4d6a8b0c1e3f5a7b9c0d2e4f";
const token = "9f2c" + "ab".repeat(22);
const marker = `{"server":"YarmiplayServerTV","version":"1.6.0","protocol":1,"capabilities":{"fileRelay":true,"jellyfin":false,"https":false},"access":"open","device":"none"}`;
const session = (relay = true) =>
  `{"Yarmiplay":{"capabilities":{"fileRelay":${relay},"https":false,"jellyfin":false},"jellyfin":{"available":false},"session":{"protocol":1,"token":"${token}"}}}`;
const offer: RelayOffer = { name: "Show - S01E01.mkv", size: 1534098432, duration: 1422.5, quickHash: "3b0c".repeat(16) };

function hello(opts: { room?: string; marker?: string; managedRooms?: boolean | null } = {}) {
  const ext = opts.marker ? `,"yarmiplay":${opts.marker}` : "";
  const managed = opts.managedRooms == null ? "" : `,"managedRooms":${opts.managedRooms}`;
  return `{"Hello":{"username":"ana","room":{"name":"${opts.room ?? "movie night"}"},"version":"1.2.255","realversion":"1.7.6","features":{"readiness":true,"chat":true${ext}${managed}}}}`;
}

describe("SyncplayClient", () => {
  const clients: SyncplayClient[] = [];
  let transports: ScriptedTransport[];
  let events: SyncplayEvent[];
  let logs: string[];
  let keys: TestDeviceAuth;

  afterEach(() => clients.splice(0).forEach((c) => c.close()));

  function client(opts: { deviceAuth?: DeviceAuth | null; requestAccess?: boolean; room?: string; saved?: Record<string, string> } = {}) {
    transports = [];
    events = [];
    logs = [];
    keys = new TestDeviceAuth();
    const factory: TransportFactory = async () => {
      const t = new ScriptedTransport();
      transports.push(t);
      return t;
    };
    const c = new SyncplayClient(
      {
        host: "yarmi.example",
        port: 8999,
        username: "ana",
        room: opts.room ?? "movie night",
        useTls: false,
        deviceAuth: opts.deviceAuth === undefined ? keys : opts.deviceAuth,
        appVersion: "2.0.0",
        requestAccess: opts.requestAccess ?? true,
      },
      new StillPlayer(),
      undefined,
      () => Date.now() / 1000,
      factory,
      (l) => logs.push(l),
    );
    c.savedOperatorPassword = (room) => opts.saved?.[room] ?? null;
    c.onEvent((e) => events.push(e));
    c.start();
    clients.push(c);
    return c;
  }

  const transport = () => eventually("a connection", () => transports[transports.length - 1]);
  const yarmiplaySent = (t: ScriptedTransport) => t.sentJson().filter((m) => "Yarmiplay" in m).map((m) => m.Yarmiplay);
  const sets = (t: ScriptedTransport) => t.sentJson().filter((m) => "Set" in m).flatMap((m) => Object.entries(m.Set as Record<string, any>));

  async function connect(c: SyncplayClient, line = hello()) {
    const t = await transport();
    await eventually("hello sent", () => t.sent.length > 0);
    t.receive(line);
    await eventually("connected", () => c.state.status === "CONNECTED");
    return t;
  }

  it("the hello opts in only with a device key, as the web client", async () => {
    client();
    const t = await transport();
    const sent = await eventually("hello", () => t.sentJson().find((m) => "Hello" in m));
    expect(sent.Hello.features.yarmiplay).toEqual({ protocol: 1, client: "YarmiplayTV Web", version: "2.0.0" });
    expect(sent.Hello.version).toBe("1.2.255");
    expect(sent.Hello.realversion).toBe("1.7.6");
    expect(sent.Hello.features).toMatchObject({ sharedPlaylists: true, readiness: true, managedRooms: true, setOthersReadiness: true });

    client({ deviceAuth: null });
    const plain = await eventually("plain hello", () => transports[0]?.sentJson().find((m) => "Hello" in m));
    expect(plain.Hello.features.yarmiplay).toBeUndefined();
  });

  it("a stock server never gets a Yarmiplay command", async () => {
    const c = client();
    c.offerFiles([offer]);
    c.authorizeJellyfin("123456");
    const t = await connect(c);
    expect(c.state.yarmiplay.serverKind).toBe("SYNCPLAY");
    c.offerFiles([offer]);
    c.authorizeJellyfin("123456");
    c.reportUploadFailed("a1b2", "gone");
    await eventually("jellyfin refused locally", () => events.some((e) => e.type === "jellyfinAuthorized" && !e.ok));
    expect(yarmiplaySent(t)).toEqual([]);
    expect(c.session).toBeNull();
  });

  it("the marker and session start the extensions, and the token stays out of the log", async () => {
    const c = client();
    const t = await connect(c, hello({ marker }));
    t.receive(session());
    const started = await eventually("session", () => events.find((e) => e.type === "sessionStarted"));
    expect(started.type === "sessionStarted" && started.session.baseUrl).toBe("http://yarmi.example:8999");
    expect(c.state.yarmiplay.serverKind).toBe("YARMIPLAY");
    expect(c.state.yarmiplay.access).toBe("open");
    expect(c.state.yarmiplay.protocol).toBe(1);
    expect(logs.some((l) => l.includes(token))).toBe(false);
    expect(JSON.stringify(c.state)).not.toContain(token);
  });

  it("offers go out after the session and again whenever the server forgot them", async () => {
    const c = client();
    const t = await connect(c, hello({ marker }));
    c.offerFiles([offer]);
    t.receive(session());
    await eventually("first offer", () => yarmiplaySent(t).filter((m) => "offer" in m).length === 1);
    const sent = JSON.stringify(yarmiplaySent(t).find((m) => "offer" in m).offer.files);
    expect(sent).toContain(offer.quickHash);
    expect(sent).toContain("1534098432");

    c.offerFiles([offer]);
    expect(yarmiplaySent(t).filter((m) => "offer" in m)).toHaveLength(1);

    c.changeRoom("other");
    c.offerFiles([offer]);
    await eventually("offer after room change", () => yarmiplaySent(t).filter((m) => "offer" in m).length === 2);

    t.receive(`{"Yarmiplay":{"capabilities":{"fileRelay":false,"jellyfin":false,"https":true},"jellyfin":{"available":false}}}`);
    t.receive(`{"Yarmiplay":{"capabilities":{"fileRelay":true,"jellyfin":false,"https":true},"jellyfin":{"available":false}}}`);
    await eventually("offer after relay back on", () => yarmiplaySent(t).filter((m) => "offer" in m).length === 3);

    c.offerFiles([]);
    expect(yarmiplaySent(t).at(-1).offer.files).toEqual([]);
  });

  it("everything off ends the session and the connection turns plain", async () => {
    const c = client();
    const t = await connect(c, hello({ marker }));
    t.receive(session());
    await eventually("session", () => c.session);
    t.receive(`{"Yarmiplay":{"capabilities":{"fileRelay":false,"jellyfin":false,"https":false}}}`);
    await eventually("ended", () => events.some((e) => e.type === "sessionEnded"));
    expect(c.session).toBeNull();
    expect(c.state.yarmiplay.serverKind).toBe("SYNCPLAY");
    const before = yarmiplaySent(t).length;
    c.offerFiles([offer]);
    c.reportUploadFailed("x", "y");
    expect(yarmiplaySent(t)).toHaveLength(before);
  });

  it("relay lists, uploads, jellyfin answers and room settings arrive as state and events", async () => {
    const c = client();
    const t = await connect(c, hello({ marker }));
    t.receive(session());
    t.receive(
      `{"Yarmiplay":{"files":[{"id":"5d41","name":"Show - S01E01.mkv","size":1534098432,"duration":1422.5,"quickHash":"3b0c","sources":2,"cachedBytes":268435456,"rate":5242880},{"id":"x","name":"Old.mkv","size":10,"sources":0,"cachedBytes":4}]}}`,
    );
    expect(c.state.yarmiplay.relayFiles[0]).toEqual({
      id: "5d41", name: "Show - S01E01.mkv", size: 1534098432, duration: 1422.5, quickHash: "3b0c", sources: 2, cachedBytes: 268435456, rate: 5242880,
    });
    t.receive(`{"Yarmiplay":{"upload":{"id":"a1b2","file":"5d41","size":1534098432,"quickHash":"3b0c","offset":268435456,"length":33554432}}}`);
    t.receive(`{"Yarmiplay":{"uploadCancel":{"id":"a1b2"}}}`);
    t.receive(`{"Yarmiplay":{"jellyfinAuthorize":{"code":"123456","ok":false,"error":"Too many attempts"}}}`);
    expect(events).toContainEqual({
      type: "uploadRequested",
      request: { id: "a1b2", file: "5d41", size: 1534098432, quickHash: "3b0c", offset: 268435456, length: 33554432 },
    });
    expect(events).toContainEqual({ type: "uploadCancelled", id: "a1b2" });
    expect(events).toContainEqual({ type: "jellyfinAuthorized", code: "123456", ok: false, error: "Too many attempts" });

    expect(c.setRoomAutoSkip(true)).toBe(false);
    t.receive(`{"Yarmiplay":{"capabilities":{"fileRelay":true,"jellyfin":false,"https":false,"roomSettings":true}}}`);
    t.receive(`{"Yarmiplay":{"roomSettings":{"autoSkip":false}}}`);
    expect(c.setRoomAutoSkip(true)).toBe(true);
    expect(yarmiplaySent(t).some((m) => m.roomSettings?.autoSkip === true)).toBe(true);
    t.receive(`{"Yarmiplay":{"roomSettings":{"autoSkip":true,"setBy":"bob"}}}`);
    expect(events.filter((e) => e.type === "roomSettingsChanged")).toEqual([
      { type: "roomSettingsChanged", settings: { autoSkip: false, setBy: null }, first: true },
      { type: "roomSettingsChanged", settings: { autoSkip: true, setBy: "bob" }, first: false },
    ]);
    c.changeRoom("anime club");
    expect(c.state.yarmiplay.roomSettings).toBeNull();
  });

  it("a challenge is answered with a signature the server can check, then pending and approved", async () => {
    const c = client();
    const t = await transport();
    const nonce = "q83vEjRWeJA0Vni8mY7cE3A2m9vLz5n0kQ1y8f3dXeM=";
    t.receive(`{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"${serverId}","nonce":"${nonce}","access":"approved"}}}`);
    const auth = (await eventually("auth", () => yarmiplaySent(t).find((m) => "auth" in m))).auth;
    const spki = Uint8Array.from(atob(auth.publicKey), (ch) => ch.charCodeAt(0));
    expect(Array.from(spki, (b) => b.toString(16).padStart(2, "0")).join("").startsWith(Yarmiplay.P256_SPKI_PREFIX)).toBe(true);
    // Node's own verifier takes the DER signature, as the server's does.
    const { createPublicKey, verify } = await import("node:crypto");
    const key = createPublicKey({ key: Buffer.from(spki), format: "der", type: "spki" });
    const der = Buffer.from(auth.signature, "base64");
    expect(verify("sha256", Buffer.from(signedMessage(serverId, nonce)), key, der)).toBe(true);
    expect(auth.deviceName).toBe("Living room TV");
    expect(auth.requestAccess).toBe(true);
    expect(c.state.yarmiplay.serverId).toBe(serverId);

    const fp = fingerprint(spki);
    t.receive(`{"Yarmiplay":{"status":{"state":"pending","fingerprint":"${fp}"}}}`);
    t.receive(`{"Yarmiplay":{"status":{"state":"approved","fingerprint":"${fp}"}}}`);
    t.receive(hello({ marker: marker.replace('"none"', '"approved"') }));
    await eventually("logged in", () => c.state.status === "CONNECTED");
    expect(c.state.yarmiplay.approvedDevice).toBe(true);
    expect(events.filter((e) => e.type === "deviceStateChanged").map((e) => e.type === "deviceStateChanged" && e.state)).toEqual([
      { kind: "pending", fingerprint: fp },
      { kind: "approved" },
    ]);
  });

  it("a silent check doesn't ask for access, and a bad challenge stops the client", async () => {
    client({ requestAccess: false });
    let t = await transport();
    t.receive(`{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"${serverId}","nonce":"bm9uY2U=","access":"approved"}}}`);
    expect((await eventually("auth", () => yarmiplaySent(t).find((m) => "auth" in m))).auth.requestAccess).toBe(false);

    const c = client();
    t = await transport();
    t.receive(`{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"../../etc","nonce":"bm9uY2U=","access":"approved"}}}`);
    await eventually("stopped", () => events.some((e) => e.type === "disconnected" && !e.willReconnect));
    expect(yarmiplaySent(t).some((m) => "auth" in m)).toBe(false);
    await eventually("disconnected", () => c.state.status === "DISCONNECTED");
  });

  it("no reconnect after the host refuses", async () => {
    for (const state of ["denied", "required", "expired"]) {
      const c = client();
      const t = await transport();
      t.receive(`{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"${serverId}","nonce":"bm9uY2U=","access":"approved"}}}`);
      t.receive(`{"Yarmiplay":{"status":{"state":"${state}","fingerprint":"3F2A-91BC-04DE-7710"}}}`);
      t.receive(`{"Error":{"message":"The host denied this device"}}`);
      await eventually(`stopped after ${state}`, () => events.some((e) => e.type === "disconnected" && !e.willReconnect));
      expect(c.state.yarmiplay.device.kind).toBe(state);
      await sleep(100);
      expect(transports).toHaveLength(1);
      c.close();
    }
  });

  it("a reconnect checks the key silently", async () => {
    const c = client();
    const first = await connect(c, hello({ marker }));
    first.close();
    const second = await eventually("reconnect", () => transports[1], 4000);
    second.receive(`{"Yarmiplay":{"challenge":{"protocol":1,"serverId":"${serverId}","nonce":"bm9uY2U=","access":"approved"}}}`);
    expect((await eventually("auth", () => yarmiplaySent(second).find((m) => "auth" in m))).auth.requestAccess).toBe(false);
  });

  it("peers with an extension session are marked", async () => {
    const c = client();
    const t = await connect(c, hello({ marker }));
    t.receive(`{"List":{"movie night":{"ana":{"position":0,"file":{},"features":{"yarmiplay":{"protocol":1}}},"bob":{"position":0,"file":{},"features":{"chat":true}}}}}`);
    expect(Object.fromEntries(c.state.users.map((u) => [u.name, u.yarmiplay]))).toEqual({ ana: true, bob: false });
    t.receive(`{"Set":{"user":{"cleo":{"room":{"name":"movie night"},"event":{"joined":true,"version":"1.7.6","features":{"yarmiplay":{"protocol":1}}}}}}}`);
    t.receive(`{"Set":{"user":{"dan":{"room":{"name":"movie night"},"event":{"joined":true,"version":"1.7.6","features":{"chat":true}}}}}}`);
    expect(c.state.users.map((u) => u.name)).toEqual(["ana", "bob", "cleo", "dan"]);
    expect(c.state.users.find((u) => u.name === "cleo")!.yarmiplay).toBe(true);
    expect(c.state.users.find((u) => u.name === "dan")!.yarmiplay).toBe(false);
  });

  // --- Managed rooms (ManagedRoomsTest.kt) ---

  const managed = "+movie night:D6A85289CDB5";
  const list = (room: string, ...users: [string, boolean][]) =>
    `{"List":{"${room}":{${users.map(([name, op]) => `"${name}":{"position":0,"file":{},"controller":${op},"isReady":true}`).join(",")}}}}`;

  it("creating a managed room asks the server, joins the room it names and identifies there", async () => {
    const c = client({ deviceAuth: null });
    const t = await connect(c, hello({ managedRooms: true }));
    expect(c.createManagedRoom("  movie night  ")).toBe(true);
    const first = sets(t).find(([k]) => k === "controllerAuth")![1];
    expect(first.room).toBe("movie night");
    t.receive(`{"Set":{"newControlledRoom":{"password":"${first.password}","roomName":"${managed}"}}}`);
    expect(sets(t).filter(([k]) => ["controllerAuth", "room"].includes(k)).map(([k]) => k)).toEqual(["controllerAuth", "room", "controllerAuth"]);
    const second = sets(t).filter(([k]) => k === "controllerAuth")[1][1];
    expect(second).toEqual({ room: managed, password: first.password });
    expect(events).toContainEqual({ type: "managedRoomCreated", room: managed, password: first.password });
    t.receive(`{"Set":{"controllerAuth":{"user":"ana","room":"${managed}","success":true}}}`);
    expect(c.state.room).toBe(managed);
    expect(c.state.users.find((u) => u.name === "ana")?.isController).toBe(true);
  });

  it("a newControlledRoom we didn't ask for is ignored", async () => {
    const c = client({ deviceAuth: null });
    const t = await connect(c, hello({ managedRooms: true }));
    t.receive(`{"Set":{"newControlledRoom":{"password":"AB-123-456","roomName":"${managed}"}}}`);
    expect(c.state.room).toBe("movie night");
    expect(events.some((e) => e.type === "managedRoomCreated")).toBe(false);
  });

  it("ready set by someone else is reported with who set it, and an operator sets others' ready", async () => {
    const c = client({ deviceAuth: null, room: managed });
    const t = await connect(c, hello({ room: managed, managedRooms: true }));
    t.receive(list(managed, ["ana", true], ["bob", false]));
    t.receive(`{"Set":{"ready":{"username":"ana","isReady":true,"manuallyInitiated":true,"setBy":"bob"}}}`);
    expect(events.filter((e) => e.type === "readySetBy")).toEqual([{ type: "readySetBy", user: "ana", setBy: "bob", isReady: true }]);
    c.setOthersReady("bob", true);
    expect(sets(t).find(([k, v]) => k === "ready" && v.username === "bob")![1]).toEqual({ username: "bob", isReady: true, manuallyInitiated: true });
  });

  it("a saved password is sent once after joining, and again after a reconnect", async () => {
    const c = client({ deviceAuth: null, room: managed, saved: { [managed]: "ab-123-456" } });
    const t = await connect(c, hello({ room: managed, managedRooms: true }));
    expect(sets(t).filter(([k]) => k === "controllerAuth").map(([, v]) => v.password)).toEqual(["AB-123-456"]);
    c.changeRoom(managed);
    t.close();
    const second = await eventually("reconnect", () => transports[1], 4000);
    second.receive(hello({ room: managed, managedRooms: true }));
    await eventually("sent again", () => sets(second).some(([k]) => k === "controllerAuth"));
  });

  it("without managedRooms in the server's Hello nothing is sent", async () => {
    const c = client({ deviceAuth: null });
    const t = await connect(c, hello({ managedRooms: null }));
    expect(c.createManagedRoom("movie night")).toBe(false);
    c.setOthersReady("bob", true);
    expect(sets(t).some(([k, v]) => k === "controllerAuth" || (k === "ready" && v.username))).toBe(false);
  });

  // --- The room's state and playlist ---

  it("the room's playlist is announced on joining as nobody's change, the first index follows the room", async () => {
    const c = client({ deviceAuth: null });
    const t = await connect(c);
    t.receive(`{"Set":{"playlistChange":{"user":"bob","files":["a.mkv","b.mkv"]}}}`);
    expect(events.find((e) => e.type === "playlistChanged")).toEqual({ type: "playlistChanged", files: ["a.mkv", "b.mkv"], setBy: null, previous: [] });
    t.receive(`{"Set":{"playlistIndex":{"user":"bob","index":1}}}`);
    expect(events.find((e) => e.type === "switchToPlaylistItem")).toMatchObject({ index: 1, filename: "b.mkv", resetPosition: false });
    t.receive(`{"Set":{"playlistIndex":{"user":"bob","index":0}}}`);
    expect(events.filter((e) => e.type === "switchToPlaylistItem").at(-1)).toMatchObject({ index: 0, filename: "a.mkv", resetPosition: true });
    expect(events).toContainEqual({ type: "notification", message: "bob selected 'a.mkv'", isError: false });
  });

  it("an empty room's playlist counts as received when the List answers", async () => {
    const c = client({ deviceAuth: null });
    const t = await connect(c);
    expect(c.state.playlistReceived).toBe(false);
    t.receive(list("movie night", ["ana", false]));
    expect(c.state.playlistReceived).toBe(true);
    expect(events.find((e) => e.type === "playlistChanged")).toEqual({ type: "playlistChanged", files: [], setBy: null, previous: [] });
  });

  it("a server State is answered, and a local change waits for the server's echo", async () => {
    const c = client({ deviceAuth: null });
    const t = await connect(c);
    t.receive(`{"State":{"playstate":{"position":10,"paused":false,"doSeek":false,"setBy":"bob"},"ping":{"latencyCalculation":1000.5,"serverRtt":0}}}`);
    const reply = t.sentJson().filter((m) => "State" in m).at(-1)!.State;
    expect(reply.ping.latencyCalculation).toBe(1000.5);
    expect(reply.playstate).toBeDefined();
    t.receive(`{"State":{"ignoringOnTheFly":{"server":1},"playstate":{"position":20,"paused":true,"doSeek":true,"setBy":"bob"},"ping":{"latencyCalculation":1001}}}`);
    const echo = t.sentJson().filter((m) => "State" in m).at(-1)!.State;
    expect(echo.ignoringOnTheFly).toEqual({ server: 1 });
    expect(c.state.globalPaused).toBe(true);
  });

  it("chat goes out as a plain string and comes in as an event", async () => {
    const c = client({ deviceAuth: null });
    const t = await connect(c);
    c.sendChat("hello there");
    expect(t.sentJson().find((m) => "Chat" in m)).toEqual({ Chat: "hello there" });
    t.receive(`{"Chat":{"username":"bob","message":"hi"}}`);
    expect(events).toContainEqual({ type: "chat", username: "bob", message: "hi" });
  });

  it("a server password is sent as its MD5", async () => {
    const t0: ScriptedTransport[] = [];
    const c = new SyncplayClient(
      { host: "h", port: 1, username: "ana", room: "r", password: "secret", useTls: false },
      new StillPlayer(),
      undefined,
      undefined,
      async () => {
        const t = new ScriptedTransport();
        t0.push(t);
        return t;
      },
    );
    clients.push(c);
    c.start();
    const hello = await eventually("hello", () => t0[0]?.sentJson().find((m) => "Hello" in m));
    expect(hello.Hello.password).toBe("5ebe2294ecd0e0f08eab7690d2a6ee69");
  });
});

describe("helpers", () => {
  it("playlist edits keep pointing at the current file", () => {
    const old = ["a.mkv", "b.mkv", "c.mkv"];
    expect(validIndexFromNewPlaylist(old, 1, ["x.mkv", "a.mkv", "b.mkv", "c.mkv"])).toBe(2);
    expect(validIndexFromNewPlaylist(old, 2, ["a.mkv", "c.mkv"])).toBe(1);
    expect(validIndexFromNewPlaylist([], null, ["a.mkv"])).toBe(0);
    expect(validIndexFromNewPlaylist(old, 1, ["a.mkv", "c.mkv"])).toBe(1);
  });

  it("the end of a file advances, loops or stops like Syncplay", () => {
    expect(nextPlaylistIndex(3, 1, false, false)).toBe(2);
    expect(nextPlaylistIndex(3, 2, false, true)).toBeNull();
    expect(nextPlaylistIndex(3, 2, true, false)).toBe(0);
    expect(nextPlaylistIndex(1, 0, true, false)).toBeNull();
    expect(nextPlaylistIndex(1, 0, false, true)).toBe(0);
  });

  it("quick hash, fingerprint and signed message match the server", async () => {
    // Vectors from YarmiplayServerTV's scripts/fake_peer.py quick_hash.
    const big = Uint8Array.from({ length: 3 * (1 << 20) + 5 }, (_, i) => (i * 31 + 7) % 251);
    const small = Uint8Array.from({ length: 1000 }, (_, i) => (i * 31 + 7) % 251);
    expect(await quickHash(new Blob([big]))).toBe("f0395d68e53fb45c9201eff2d0f08e0cb32416d6c3b035e1ecb97d5fd716912a");
    expect(await quickHash(new Blob([small]))).toBe("21c811b45c3053ff584fa2324b85092d33020d69fab2295e8d1c1595c00533c7");
    expect(fingerprint(Uint8Array.from({ length: 91 }, (_, i) => i))).toMatch(/^[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$/);
    expect(new TextDecoder().decode(signedMessage(serverId, "q83v+/="))).toBe(`YarmiplayServerTV-device-auth-v1\n${serverId}\nq83v+/=`);
    expect(isValidServerId(serverId)).toBe(true);
    expect(isValidServerId(serverId.toUpperCase())).toBe(false);
    expect(isValidServerId("abc")).toBe(false);
    expect(redact(`{"session":{"token":"${token}"}}`)).toBe(`{"session":{"token":"…"}}`);
  });

  it("DER signatures: leading zeros dropped and a zero added before a high bit", () => {
    const raw = new Uint8Array(64);
    raw[0] = 0x00;
    raw[1] = 0x7f;
    raw[32] = 0x80;
    const der = rawToDer(raw);
    expect(der[0]).toBe(0x30);
    expect(Array.from(der.subarray(2, 4))).toEqual([0x02, 31]);
    const sStart = 2 + 2 + 31;
    expect(Array.from(der.subarray(sStart, sStart + 3))).toEqual([0x02, 33, 0x00]);
    expect(der[1]).toBe(der.length - 2);
  });
});
