import { SyncEngine, type SyncHost } from "./engine";
import * as Filenames from "./filenames";
import { md5Hex } from "./hash";
import * as ManagedRooms from "./managedRooms";
import { Constants, defaultSyncSettings, formatTime, systemClock, type Clock, type FileInfo, type PlayerAdapter, type SyncSettings } from "./models";
import { PingService } from "./ping";
import { firstOpen, LAN_HEAD_START_MS, openWebSocket, type Route, type Transport, type TransportFactory } from "./transport";
import {
  allOff,
  emptyYarmiplayInfo,
  fingerprint,
  isValidServerId,
  noCapabilities,
  signedMessage,
  Yarmiplay,
  type Capabilities,
  type DeviceAuth,
  type DeviceState,
  type RelayFile,
  type RelayOffer,
  type RoomSettings,
  type ServerTls,
  type SharedJellyfin,
  type UploadRequest,
  type YarmiplayInfo,
  type YarmiplaySession,
} from "./yarmiplay";

export interface SyncplayConfig {
  host: string;
  port: number;
  username: string;
  room: string;
  /** Plain-text server password; hashed with MD5 before sending, like the desktop client. */
  password?: string | null;
  /** wss:// (always, except for development against a server on this computer). */
  useTls: boolean;
  autoReconnect?: boolean;
  /** This device's keys; with them the Hello opts in to the YarmiplayServerTV extensions. */
  deviceAuth?: DeviceAuth | null;
  appVersion?: string;
  /** Ask the host to approve this device when its key isn't approved yet; reconnects never ask. */
  requestAccess?: boolean;
  /** The server's LAN name (its certificate covers it too), tried after a moment, for routers that don't loop back. */
  lanName?: string | null;
}

export type ConnectionStatus = "DISCONNECTED" | "CONNECTING" | "CONNECTED" | "RECONNECTING";

export interface RoomUser {
  name: string;
  room: string;
  file: FileInfo | null;
  /** null when the user's client doesn't support readiness. */
  isReady: boolean | null;
  /** A room operator: in a managed room, only operators control playback and the playlist. */
  isController: boolean;
  /** The user's client has a YarmiplayServerTV extension session. */
  yarmiplay: boolean;
}

export interface RoomState {
  status: ConnectionStatus;
  username: string;
  room: string;
  serverVersion: string | null;
  motd: string | null;
  tls: boolean;
  users: RoomUser[];
  playlist: string[];
  /** Whether the server has sent the room's playlist since we (re)joined the room. */
  playlistReceived: boolean;
  playlistIndex: number | null;
  isReady: boolean | null;
  globalPaused: boolean;
  globalPosition: number;
  lastSetBy: string | null;
  serverFeatures: Record<string, unknown>;
  rttMillis: number;
  yarmiplay: YarmiplayInfo;
  /** How this connection reached the server; null while not connected. */
  route: { host: string; atHome: boolean } | null;
}

export const others = (s: RoomState) => s.users.filter((u) => u.name !== s.username);
export const currentPlaylistFile = (s: RoomState) => (s.playlistIndex === null ? null : (s.playlist[s.playlistIndex] ?? null));
/** The server supports managed rooms (its Hello says so; a missing key means no). */
export const hasManagedRooms = (s: RoomState) => s.serverFeatures.managedRooms === true;
export const inManagedRoom = (s: RoomState) => hasManagedRooms(s) && ManagedRooms.isManaged(s.room);
export const isOperator = (s: RoomState) => inManagedRoom(s) && s.users.some((u) => u.name === s.username && u.isController);
/** In a managed room without being its operator: playback and the playlist follow the operators. */
export const followsOperators = (s: RoomState) => inManagedRoom(s) && !isOperator(s);
export const relayActive = (y: YarmiplayInfo) => y.session && y.capabilities.fileRelay;
export const roomSettingsShared = (y: YarmiplayInfo) => y.session && y.capabilities.roomSettings;

/** One-off things that happened; persistent state lives in [RoomState]. */
export type SyncplayEvent =
  | { type: "connected"; username: string; room: string; motd: string | null; tls: boolean }
  | { type: "disconnected"; reason: string | null; willReconnect: boolean }
  | { type: "notification"; message: string; isError: boolean }
  | { type: "chat"; username: string; message: string }
  /** The room selected an entry; [resetPosition] is false only for the first index after joining. */
  | { type: "switchToPlaylistItem"; index: number; filename: string; setBy: string | null; resetPosition: boolean }
  /** [setBy] is null for the room's list sent on joining it, which comes even when empty. */
  | { type: "playlistChanged"; files: string[]; setBy: string | null; previous: string[] }
  | { type: "advancePlaylist"; nextIndex: number; filename: string }
  | { type: "managedRoomCreated"; room: string; password: string }
  | { type: "controllerAuth"; user: string; room: string; success: boolean; password: string | null }
  | { type: "readySetBy"; user: string; setBy: string; isReady: boolean }
  | { type: "deviceStateChanged"; state: DeviceState }
  | { type: "sessionStarted"; session: YarmiplaySession }
  | { type: "sessionEnded" }
  | { type: "uploadRequested"; request: UploadRequest }
  | { type: "uploadCancelled"; id: string }
  | { type: "roomSettingsChanged"; settings: RoomSettings; first: boolean }
  | { type: "jellyfinAuthorized"; code: string; ok: boolean; error: string | null };

type Json = Record<string, unknown>;

const isObject = (v: unknown): v is Json => typeof v === "object" && v !== null && !Array.isArray(v);
const str = (o: Json, key: string): string | null => {
  const v = o[key];
  return typeof v === "string" ? v : typeof v === "number" || typeof v === "boolean" ? String(v) : null;
};
const bool = (o: Json, key: string): boolean => o[key] === true;
const num = (o: Json, key: string): number => (typeof o[key] === "number" ? (o[key] as number) : 0);

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

const initialState = (config: SyncplayConfig): RoomState => ({
  status: "DISCONNECTED",
  username: config.username,
  room: config.room,
  serverVersion: null,
  motd: null,
  tls: false,
  users: [],
  playlist: [],
  playlistReceived: false,
  playlistIndex: null,
  isReady: null,
  globalPaused: true,
  globalPosition: 0,
  lastSetBy: null,
  serverFeatures: {},
  rttMillis: 0,
  yarmiplay: emptyYarmiplayInfo(),
  route: null,
});

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Syncplay client (SyncplayClient.kt, for the browser): connects to YarmiplayServerTV over a WebSocket, joins a room
 * and keeps [player] in sync with it.
 */
export class SyncplayClient {
  config: SyncplayConfig;
  private _state: RoomState;
  private readonly stateListeners = new Set<(s: RoomState) => void>();
  private readonly eventListeners = new Set<(e: SyncplayEvent) => void>();
  private _session: YarmiplaySession | null = null;

  private readonly engine: SyncEngine;
  private readonly ping: PingService;

  private transport: Transport | null = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private stopped = true;
  private fatalError: string | null = null;
  private loop: Promise<void> | null = null;

  private logged = false;
  private clientIgnoringOnTheFly = 0;
  private serverIgnoringOnTheFly = 0;
  private hadFirstPlaylistIndex = false;
  /** The server sends the room's playlist after a Hello or room change, before answering the List that follows. */
  private awaitingRoomPlaylist = false;
  private lastMessageTime = 0;

  private currentFile: FileInfo | null = null;
  private queuedFilename: string | null = null;
  private lastPlaylistIndexChange = 0;
  private myReady: boolean | null = null;
  private readonly users = new Map<string, RoomUser>();

  /** Logged in at least once: from then on reconnects check the device key silently. */
  private hadLogin = false;
  private sessionToken: string | null = null;
  private sessionProtocol = 0;
  private lastFingerprint = "";

  private pendingCreatePassword: string | null = null;
  private identifiedRoom: string | null = null;
  private readonly sentPasswords = new Map<string, string>();

  /** A saved operator password for a managed room (the full `+name:HASH`), sent once after joining it. */
  savedOperatorPassword: (room: string) => string | null = () => null;

  private offer: RelayOffer[] = [];
  private offerSent: RelayOffer[] | null = null;

  constructor(
    config: SyncplayConfig,
    private readonly player: PlayerAdapter,
    settings: SyncSettings = defaultSyncSettings(),
    private readonly clock: Clock = systemClock,
    private readonly transportFactory: TransportFactory = openWebSocket,
    private readonly log: (line: string) => void = () => {},
  ) {
    this.config = { autoReconnect: true, requestAccess: true, ...config };
    this._state = initialState(config);
    this.ping = new PingService(clock);
    this.engine = new SyncEngine(player, clock, settings, this.engineHost());
  }

  // --- Observing -----------------------------------------------------------------

  get state(): RoomState {
    return this._state;
  }

  /** The live extension session, for the relay's and the shared Jellyfin's HTTP requests. */
  get session(): YarmiplaySession | null {
    return this._session;
  }

  subscribe(listener: (s: RoomState) => void): () => void {
    this.stateListeners.add(listener);
    listener(this._state);
    return () => this.stateListeners.delete(listener);
  }

  onEvent(listener: (e: SyncplayEvent) => void): () => void {
    this.eventListeners.add(listener);
    return () => this.eventListeners.delete(listener);
  }

  get settings(): SyncSettings {
    return this.engine.settings;
  }

  set settings(value: SyncSettings) {
    this.engine.settings = value;
  }

  // --- Public API ------------------------------------------------------------------

  start() {
    if (!this.stopped) return;
    this.stopped = false;
    this.fatalError = null;
    this.loop = this.connectionLoop();
    this.pollTimer = setInterval(() => this.tick(), Constants.PLAYER_ASK_DELAY_MS);
  }

  stop() {
    this.stopped = true;
    this.transport?.close();
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.pollTimer = null;
    this.endSession();
    this.update({ status: "DISCONNECTED" });
  }

  close() {
    this.stop();
    this.stateListeners.clear();
    this.eventListeners.clear();
  }

  /** Waits for the connection loop to end (tests). */
  async finished(): Promise<void> {
    await this.loop;
  }

  /**
   * Reports the file that just finished loading in the player (or null when nothing is loaded). [resetPosition]
   * comes from switchToPlaylistItem; files opened directly pass false so playback jumps to the room position.
   */
  fileLoaded(file: FileInfo | null, resetPosition = false) {
    this.currentFile = file;
    this.queuedFilename = null;
    const me = this.users.get(this._state.username);
    if (me) this.users.set(me.name, { ...me, file });
    this.publishUsers();
    if (!file) return;
    this.engine.onFileLoaded(resetPosition);
    this.sendFile();
    this.changeToPlaylistIndexFromFilename(file.name);
  }

  /** Call when the player starts opening a file; follow up with fileLoaded once it's ready. */
  fileLoading() {
    this.engine.onFileLoading();
  }

  setReady(ready: boolean, manuallyInitiated = true) {
    this.changeReadyState(ready, manuallyInitiated);
  }

  toggleReady() {
    this.changeReadyState(!(this.myReady ?? false), true);
  }

  sendChat(message: string) {
    if (this.logged && message.trim()) this.send({ Chat: message });
  }

  changeRoom(room: string) {
    this.applyRoomChange(room);
    this.identifyWithSavedPassword();
  }

  /**
   * Makes a managed room named [baseName] with a new operator password, and joins it as its operator. False,
   * sending nothing, when the server has no managed rooms or the name doesn't fit.
   */
  createManagedRoom(baseName: string): boolean {
    const name = baseName.trim();
    if (!hasManagedRooms(this._state) || !ManagedRooms.isValidBaseName(name) || !this.logged) return false;
    const password = ManagedRooms.generatePassword();
    this.pendingCreatePassword = password;
    this.sendControllerAuth(name, password);
    return true;
  }

  /** Asks to become an operator of the managed room we're in; answered with controllerAuth. */
  identifyAsOperator(password: string) {
    const room = this._state.room;
    if (!this.logged || !inManagedRoom(this._state)) return;
    this.identifiedRoom = room;
    this.sendControllerAuth(room, ManagedRooms.normalizePassword(password));
  }

  /** As an operator, marks [username] ready or not ready; the server ignores it from anyone else. */
  setOthersReady(username: string, isReady: boolean) {
    if (!this.logged || !hasManagedRooms(this._state) || username === this._state.username) return;
    this.sendSet({ ready: { username, isReady, manuallyInitiated: true } });
  }

  /** Offers [files] to the room's file relay, replacing the previous offer (empty withdraws it). */
  offerFiles(files: RelayOffer[]) {
    this.offer = files.slice(0, Yarmiplay.MAX_OFFERED_FILES);
    this.sendOfferIfNeeded();
  }

  /** Tells the server this device can't serve upload [id], so it asks another seeder at once. */
  reportUploadFailed(id: string, error: string) {
    if (this._session) this.sendYarmiplay({ uploadFailed: { id, error } });
  }

  /** Asks the server to approve Jellyfin Quick Connect [code] for its guest account. */
  authorizeJellyfin(code: string) {
    if (!this._session || !this._state.yarmiplay.capabilities.jellyfin) {
      this.emit({ type: "jellyfinAuthorized", code, ok: false, error: "The host isn't sharing Jellyfin" });
      return;
    }
    this.sendYarmiplay({ jellyfinAuthorize: { code } });
  }

  /** Turns the room's automatic skipping of openings and endings on or off, for everyone in it. */
  setRoomAutoSkip(on: boolean): boolean {
    if (!this._session || !this._state.yarmiplay.capabilities.roomSettings) return false;
    this.sendYarmiplay({ roomSettings: { autoSkip: on } });
    return true;
  }

  /** Asks the room to switch to playlist entry [index]; the switch happens when the server echoes it. */
  selectPlaylistIndex(index: number) {
    this.sendPlaylistIndex(index);
  }

  setPlaylist(files: string[]) {
    this.changePlaylist(files);
  }

  /** Adds [filename] to the playlist if needed and makes the room switch to it. */
  playInRoom(filename: string) {
    const files = this._state.playlist;
    const existing = files.findIndex((f) => Filenames.same(f, filename));
    if (existing >= 0) {
      this.sendPlaylistIndex(existing);
    } else {
      const newFiles = [...files, filename];
      this.update({ playlist: newFiles });
      this.sendSet({ playlistChange: { files: newFiles } });
      this.sendPlaylistIndex(newFiles.length - 1);
    }
  }

  // --- Connection lifecycle ---------------------------------------------------------

  private async connectionLoop() {
    let attempt = 0;
    while (!this.stopped) {
      this.update({ status: attempt === 0 ? "CONNECTING" : "RECONNECTING" });
      let error: Error | null = null;
      try {
        await this.runSession();
      } catch (e) {
        error = e as Error;
      }
      const wasLogged = this.logged;
      this.endSession();
      if (this.stopped) break;
      const fatal = this.fatalError;
      const reason = fatal ?? error?.message ?? "Connection closed";
      this.log(`Disconnected: ${reason}`);
      if (fatal !== null || !this.config.autoReconnect) {
        this.emit({ type: "disconnected", reason, willReconnect: false });
        this.emit({ type: "notification", message: reason, isError: true });
        this.stopped = true;
        if (this.pollTimer) clearInterval(this.pollTimer);
        break;
      }
      this.emit({ type: "disconnected", reason, willReconnect: true });
      if (wasLogged) attempt = 0;
      attempt++;
      this.update({ status: "RECONNECTING" });
      await sleep(Math.min(30, 2 ** (attempt - 1)) * 1000);
    }
    this.update({ status: "DISCONNECTED" });
  }

  /** One connection, from opening to closing; rejects with why it closed. */
  private async runSession(): Promise<void> {
    this.update({ yarmiplay: emptyYarmiplayInfo(), route: null });
    const host = this.config.host.trim();
    const routes: Route[] = [{ host, port: this.config.port, delayMs: 0, atHome: false }];
    const lan = this.config.lanName?.trim();
    if (lan && this.config.useTls && lan.toLowerCase() !== host.toLowerCase()) {
      routes.push({ host: lan, port: this.config.port, delayMs: LAN_HEAD_START_MS, atHome: true });
    }
    const opened = await firstOpen(routes, this.config.useTls, this.transportFactory);
    if (this.stopped) {
      if ("transport" in opened) opened.transport.close();
      return;
    }
    if (!("transport" in opened)) {
      throw opened.errors.get(routes[0]) ?? new Error(`Couldn't reach ${host}`);
    }
    const { route, transport } = opened;
    this.transport = transport;
    this.lastMessageTime = this.clock();
    this.update({ route: { host: route.host, atHome: route.atHome } });
    this.log(`Connected to ${route.host}:${route.port}${route.atHome ? " at home" : ""}`);

    await new Promise<void>((resolve, reject) => {
      transport.onClose = (reason) => reject(new Error(this.fatalError ?? reason));
      transport.onLine = (line) => {
        this.lastMessageTime = this.clock();
        this.log(`<< ${redact(line)}`);
        this.handleLine(line);
        if (this.fatalError !== null) {
          transport.close();
          reject(new Error(this.fatalError));
        }
      };
      this.sendHello();
      void resolve;
    });
  }

  private endSession() {
    this.logged = false;
    this.update({ route: null });
    this.transport?.close();
    this.transport = null;
    this.clientIgnoringOnTheFly = 0;
    this.serverIgnoringOnTheFly = 0;
    this.hadFirstPlaylistIndex = false;
    this.identifiedRoom = null;
    this.pendingCreatePassword = null;
    this.sessionToken = null;
    this.refreshSession();
    this.engine.onDisconnected();
  }

  private tick() {
    if (!this.logged) {
      if (this._state.yarmiplay.device.kind === "pending" && this.clock() - this.lastMessageTime > Constants.PENDING_TIMEOUT) {
        this.log("No word from the server while waiting for approval");
        this.transport?.close();
      }
      return;
    }
    if (this.engine.hasGlobalState && this.clock() - this.lastMessageTime > Constants.PROTOCOL_TIMEOUT) {
      this.log("Server timed out");
      this.transport?.close();
      return;
    }
    this.engine.poll();
  }

  // --- Incoming messages ---------------------------------------------------------

  private handleLine(line: string) {
    let message: unknown;
    try {
      message = JSON.parse(line);
    } catch {
      this.log(`Ignoring non-JSON line: ${line}`);
      return;
    }
    if (!isObject(message)) return;
    for (const [command, value] of Object.entries(message)) {
      switch (command) {
        case "Hello":
          if (isObject(value)) this.handleHello(value);
          break;
        case "Set":
          if (isObject(value)) this.handleSet(value);
          break;
        case "List":
          if (isObject(value)) this.handleList(value);
          break;
        case "State":
          if (isObject(value)) this.handleState(value);
          break;
        case "Chat":
          if (isObject(value)) this.handleChat(value);
          break;
        case "Error":
          this.handleError(value);
          break;
        case "TLS":
          break;
        case "Yarmiplay":
          if (isObject(value)) this.handleYarmiplay(value);
          break;
        default:
          this.log(`Unknown command ${command}`);
      }
    }
  }

  private handleHello(hello: Json) {
    const username = str(hello, "username") ?? this.config.username;
    const room = (isObject(hello.room) ? str(hello.room, "name") : null) ?? this.config.room;
    const version = str(hello, "realversion") ?? str(hello, "version");
    const motd = str(hello, "motd")?.trim() ? str(hello, "motd") : null;
    const features = isObject(hello.features) ? hello.features : {};
    const marker = isObject(features.yarmiplay) && str(features.yarmiplay, "server") === Yarmiplay.SERVER_NAME ? features.yarmiplay : null;
    this.logged = true;
    this.hadLogin = true;
    this.users.clear();
    this.users.set(username, user(username, room, this.currentFile, this.myReady));
    const ext = this._state.yarmiplay;
    this.update({
      status: "CONNECTED",
      username,
      room,
      serverVersion: version,
      motd,
      tls: this.transport?.tls === true,
      serverFeatures: features,
      yarmiplay: marker
        ? {
            ...ext,
            serverKind: "YARMIPLAY",
            serverVersion: str(marker, "version"),
            access: str(marker, "access"),
            approvedDevice: str(marker, "device") === "approved",
            capabilities: parseCapabilities(marker.capabilities) ?? ext.capabilities,
            tls: parseTls(marker.tls),
          }
        : ext.serverKind === "YARMIPLAY"
          ? ext
          : { ...ext, serverKind: "SYNCPLAY" },
    });
    this.publishUsers();
    this.rememberLanName();
    this.emit({ type: "connected", username, room, motd, tls: this.transport?.tls === true });

    this.sendReady(this.myReady ?? this.engine.settings.readyAtStart, false);
    this.sendFile();
    this.sendList();
    // Operator status ends with the connection; a saved password makes us one again.
    this.identifyWithSavedPassword();
  }

  private handleSet(set: Json) {
    for (const [command, value] of Object.entries(set)) {
      if (!isObject(value)) continue;
      switch (command) {
        case "room": {
          const room = str(value, "name");
          if (room !== null) this.update({ room });
          break;
        }
        case "user":
          this.handleSetUsers(value);
          break;
        case "ready":
          this.handleSetReady(value);
          break;
        case "playlistIndex":
          this.handlePlaylistIndex(value);
          break;
        case "playlistChange":
          this.handlePlaylistChange(value);
          break;
        case "controllerAuth":
          this.handleControllerAuth(value);
          break;
        case "newControlledRoom":
          this.handleNewControlledRoom(value);
          break;
      }
    }
  }

  private handleSetUsers(usersObj: Json) {
    const myRoom = this._state.room;
    const me = this._state.username;
    for (const [name, element] of Object.entries(usersObj)) {
      if (!isObject(element)) continue;
      const room = isObject(element.room) ? str(element.room, "name") : null;
      const file = parseFile(element.file);
      const event = isObject(element.event) ? element.event : null;
      if (event && "joined" in event) {
        if (room === myRoom) {
          const features = isObject(event.features) ? event.features : isObject(element.features) ? element.features : null;
          const existing = this.users.get(name);
          this.users.set(name, {
            ...user(name, room, file ?? existing?.file ?? null, existing?.isReady ?? null),
            isController: existing?.isController ?? false,
            yarmiplay: isObject(features?.yarmiplay),
          });
          if (name !== me) this.notify(`${name} joined the room`);
        }
      } else if (event && "left" in event) {
        if (this.users.delete(name) && name !== me) this.notify(`${name} left`);
      } else {
        const existing = this.users.get(name);
        if (room !== null && room !== myRoom) {
          if (existing && name !== me) this.notify(`${name} moved to room '${room}'`);
          this.users.delete(name);
        } else {
          const base = existing ?? user(name, room ?? myRoom, null, null);
          this.users.set(name, file ? { ...base, file } : base);
          if (file && name !== me && !sameFile(file, existing?.file ?? null)) {
            this.notify(`${name} is playing '${file.name}' (${formatTime(file.duration)})`);
          }
        }
      }
    }
    this.publishUsers();
  }

  private handleSetReady(ready: Json) {
    const name = str(ready, "username");
    if (name === null) return;
    const isReady = typeof ready.isReady === "boolean" ? ready.isReady : null;
    // Set by an operator of a managed room.
    const setBy = str(ready, "setBy");
    if (setBy !== null && setBy !== name && isReady !== null) this.emit({ type: "readySetBy", user: name, setBy, isReady });
    if (name === this._state.username) {
      this.myReady = isReady;
      this.update({ isReady });
    }
    const known = this.users.get(name);
    if (known) this.users.set(name, { ...known, isReady });
    this.publishUsers();
  }

  private handleList(list: Json) {
    // Servers that skip an empty room's playlist still answer the List sent after joining.
    if (this.awaitingRoomPlaylist) this.applyPlaylist([], null);
    const myRoom = this._state.room;
    const roomUsers = list[myRoom];
    if (!isObject(roomUsers)) return;
    this.users.clear();
    for (const [name, element] of Object.entries(roomUsers)) {
      if (!isObject(element)) continue;
      this.users.set(name, {
        name,
        room: myRoom,
        file: parseFile(element.file),
        isReady: typeof element.isReady === "boolean" ? element.isReady : null,
        isController: element.controller === true,
        yarmiplay: isObject(element.features) && isObject(element.features.yarmiplay),
      });
    }
    const me = this._state.username;
    if (!this.users.has(me)) this.users.set(me, user(me, myRoom, this.currentFile, this.myReady));
    this.publishUsers();
  }

  private handleState(stateObj: Json) {
    let position: number | null = null;
    let paused: boolean | null = null;
    let doSeek = false;
    let setBy: string | null = null;
    let messageAge = 0;
    let latencyCalculation: number | null = null;

    if (isObject(stateObj.ignoringOnTheFly)) {
      const ignore = stateObj.ignoringOnTheFly;
      const server = typeof ignore.server === "number" ? ignore.server : null;
      const client = typeof ignore.client === "number" ? ignore.client : null;
      if (server !== null) {
        this.serverIgnoringOnTheFly = server;
        this.clientIgnoringOnTheFly = 0;
      } else if (client !== null && client === this.clientIgnoringOnTheFly) {
        this.clientIgnoringOnTheFly = 0;
      }
    }
    if (isObject(stateObj.playstate)) {
      const ps = stateObj.playstate;
      position = typeof ps.position === "number" ? ps.position : 0;
      paused = typeof ps.paused === "boolean" ? ps.paused : null;
      doSeek = ps.doSeek === true;
      setBy = ps.setBy === null || ps.setBy === undefined ? null : String(ps.setBy);
    }
    if (isObject(stateObj.ping)) {
      const p = stateObj.ping;
      latencyCalculation = typeof p.latencyCalculation === "number" ? p.latencyCalculation : null;
      if ("clientLatencyCalculation" in p) {
        const ts = typeof p.clientLatencyCalculation === "number" ? p.clientLatencyCalculation : null;
        this.ping.receiveMessage(ts, typeof p.serverRtt === "number" ? p.serverRtt : 0);
      }
      messageAge = this.ping.getLastForwardDelay();
    }
    if (position !== null && paused !== null && this.clientIgnoringOnTheFly === 0) {
      this.engine.updateGlobalState(position, paused, doSeek, setBy, messageAge);
      this.update({ globalPaused: paused, globalPosition: position, lastSetBy: setBy, rttMillis: this.ping.getRtt() * 1000 });
    }
    const local = this.engine.getLocalState();
    this.sendState(local?.position ?? null, local?.paused ?? null, local?.doSeek ?? false, latencyCalculation, local?.stateChange ?? false);
  }

  private handleChat(o: Json) {
    const username = str(o, "username");
    const message = str(o, "message");
    if (username !== null && message !== null) this.emit({ type: "chat", username, message });
  }

  private handleError(value: unknown) {
    const message = (isObject(value) ? str(value, "message") : null) ?? JSON.stringify(value);
    if (message.includes("startTLS") && !this.logged) return;
    this.log(`Server error: ${message}`);
    this.fatalError = message;
  }

  // --- Managed rooms ---------------------------------------------------------------

  private sendControllerAuth(room: string, password: string) {
    this.sentPasswords.set(room, password);
    this.sendSet({ controllerAuth: { room, password } });
  }

  /** After joining (or rejoining) a managed room, sends its saved password once, like the official client. */
  private identifyWithSavedPassword() {
    const s = this._state;
    if (!this.logged || !inManagedRoom(s) || this.identifiedRoom === s.room || isOperator(s)) return;
    const password = this.savedOperatorPassword(s.room);
    if (!password?.trim()) return;
    this.identifiedRoom = s.room;
    this.sendControllerAuth(s.room, ManagedRooms.normalizePassword(password));
  }

  private handleControllerAuth(auth: Json) {
    const name = str(auth, "user");
    const room = str(auth, "room");
    if (name === null || room === null || typeof auth.success !== "boolean") return;
    const success = auth.success;
    const mine = name === this._state.username;
    if (success && room === this._state.room) {
      const known = this.users.get(name) ?? (mine ? user(name, room, this.currentFile, this.myReady) : null);
      if (known) this.users.set(name, { ...known, isController: true });
      this.publishUsers();
    }
    this.emit({ type: "controllerAuth", user: name, room, success, password: mine ? (this.sentPasswords.get(room) ?? null) : null });
  }

  private handleNewControlledRoom(created: Json) {
    const room = str(created, "roomName");
    const password = str(created, "password");
    // Only the answer to our own request; the server sends it to the creator alone.
    if (room === null || password === null || password !== this.pendingCreatePassword) return;
    this.pendingCreatePassword = null;
    this.applyRoomChange(room);
    this.identifiedRoom = room;
    this.sendControllerAuth(room, password);
    this.emit({ type: "managedRoomCreated", room, password });
  }

  private applyRoomChange(room: string) {
    this.config = { ...this.config, room };
    this.update({
      room,
      playlist: [],
      playlistReceived: false,
      playlistIndex: null,
      yarmiplay: { ...this._state.yarmiplay, relayFiles: [], roomSettings: null },
    });
    // The server forgets offers on a room change; the new room's playlist decides the next one.
    this.offer = [];
    this.offerSent = null;
    this.identifiedRoom = null;
    if (!this.logged) return;
    this.hadFirstPlaylistIndex = false;
    this.awaitingRoomPlaylist = true;
    this.users.clear();
    this.sendSet({ room: { name: room } });
    this.sendList();
  }

  // --- YarmiplayServerTV extension messages ------------------------------------------

  private handleYarmiplay(message: Json) {
    // Only YarmiplayServerTV sends these, and only to clients that opted in.
    if (!this.config.deviceAuth) return;
    this.updateExt((e) => ({ ...e, serverKind: "YARMIPLAY" }));
    if (isObject(message.challenge)) void this.answerChallenge(message.challenge);
    if (isObject(message.status)) this.handleDeviceStatus(message.status);
    if (isObject(message.session) && this.logged) {
      const token = str(message.session, "token");
      this.sessionToken = token ? token : null;
      const protocol = typeof message.session.protocol === "number" ? message.session.protocol : Yarmiplay.PROTOCOL;
      this.sessionProtocol = Math.min(Yarmiplay.PROTOCOL, protocol);
    }
    const wasRelayOn = this._state.yarmiplay.capabilities.fileRelay;
    const caps = parseCapabilities(message.capabilities);
    if (caps) {
      this.updateExt((e) => ({
        ...e,
        capabilities: caps,
        relayFiles: caps.fileRelay ? e.relayFiles : [],
        // Vanilla mode revokes with capabilities alone; from then on the connection is plain Syncplay.
        serverKind: allOff(caps) && !("jellyfin" in message) && !("session" in message) ? "SYNCPLAY" : e.serverKind,
      }));
      if (!caps.fileRelay) this.offerSent = null;
    }
    if (isObject(message.jellyfin)) {
      const jellyfin = parseSharedJellyfin(message.jellyfin);
      this.updateExt((e) => ({ ...e, jellyfin }));
    }
    // Present but null: the certificate went away.
    if ("tls" in message) {
      const tls = parseTls(message.tls);
      this.updateExt((e) => ({ ...e, tls }));
    }
    this.rememberLanName();
    if (Array.isArray(message.files) && this._state.yarmiplay.capabilities.fileRelay) {
      const relayFiles = message.files.map(parseRelayFile).filter((f): f is RelayFile => f !== null);
      this.updateExt((e) => ({ ...e, relayFiles }));
    }
    this.refreshSession();
    if (!wasRelayOn && this._state.yarmiplay.capabilities.fileRelay) this.offerSent = null;
    this.sendOfferIfNeeded();

    if (!this._session) return;
    if (isObject(message.upload)) {
      const request = parseUpload(message.upload);
      if (request) this.emit({ type: "uploadRequested", request });
    }
    if (isObject(message.uploadCancel)) {
      const id = str(message.uploadCancel, "id");
      if (id) this.emit({ type: "uploadCancelled", id });
    }
    if (isObject(message.jellyfinAuthorize)) {
      const code = str(message.jellyfinAuthorize, "code");
      if (code !== null) {
        this.emit({ type: "jellyfinAuthorized", code, ok: message.jellyfinAuthorize.ok === true, error: str(message.jellyfinAuthorize, "error") });
      }
    }
    if (isObject(message.roomSettings)) {
      const setBy = str(message.roomSettings, "setBy");
      const settings: RoomSettings = { autoSkip: bool(message.roomSettings, "autoSkip"), setBy: setBy ? setBy : null };
      const before = this._state.yarmiplay.roomSettings;
      this.updateExt((e) => ({ ...e, roomSettings: settings }));
      if (!before || before.autoSkip !== settings.autoSkip || before.setBy !== settings.setBy) {
        this.emit({ type: "roomSettingsChanged", settings, first: before === null });
      }
    }
  }

  private async answerChallenge(challenge: Json) {
    const auth = this.config.deviceAuth;
    if (!auth || this.logged) return;
    const serverId = str(challenge, "serverId") ?? "";
    const nonce = str(challenge, "nonce");
    if (!isValidServerId(serverId) || !nonce) {
      this.fail("The server sent an invalid device challenge");
      return;
    }
    let publicKey: Uint8Array;
    let signature: Uint8Array;
    try {
      publicKey = await auth.publicKey(serverId);
      signature = await auth.sign(serverId, signedMessage(serverId, nonce));
    } catch (e) {
      this.log(`Device key failed: ${e}`);
      this.fail("This device's key for the server couldn't be used");
      return;
    }
    this.updateExt((e) => ({ ...e, serverId, access: str(challenge, "access") ?? e.access }));
    this.sendYarmiplay({
      auth: {
        publicKey: toBase64(publicKey),
        signature: toBase64(signature),
        deviceName: auth.deviceName.slice(0, 60),
        requestAccess: (this.config.requestAccess ?? true) && !this.hadLogin,
      },
    });
    this.lastFingerprint = fingerprint(publicKey);
  }

  /** A fatal error found outside a line's handling (the device key is asynchronous): closes the connection. */
  private fail(message: string) {
    this.fatalError = message;
    this.transport?.close();
    this.transport?.onClose(message);
  }

  private handleDeviceStatus(status: Json) {
    const fp = str(status, "fingerprint") ?? this.lastFingerprint;
    const kinds = ["approved", "pending", "denied", "expired", "required", "revoked"] as const;
    const kind = str(status, "state") as (typeof kinds)[number] | null;
    if (!kind || !kinds.includes(kind)) return;
    const device: DeviceState = kind === "pending" ? { kind, fingerprint: fp } : { kind };
    const current = this._state.yarmiplay.device;
    if (current.kind === device.kind && (device.kind !== "pending" || (current as { fingerprint: string }).fingerprint === fp)) return;
    this.updateExt((e) => ({ ...e, device }));
    this.emit({ type: "deviceStateChanged", state: device });
  }

  /** Starts or ends the HTTP session when the token or the capabilities changed. */
  private refreshSession() {
    const ext = this._state.yarmiplay;
    const token = this.sessionToken;
    const active = this.logged && token !== null && ext.serverKind === "YARMIPLAY" && !allOff(ext.capabilities);
    const current = this._session;
    const baseUrl = active ? this.httpBase() : null;
    if (active && (current === null || current.baseUrl !== baseUrl)) {
      const session: YarmiplaySession = { token: token!, protocol: this.sessionProtocol, baseUrl: baseUrl! };
      this._session = session;
      if (current === null) this.offerSent = null;
      this.updateExt((e) => ({ ...e, session: true, protocol: this.sessionProtocol }));
      this.emit({ type: "sessionStarted", session });
    } else if (!active && current !== null) {
      this._session = null;
      this.offerSent = null;
      this.updateExt((e) => ({ ...e, session: false, protocol: 0, relayFiles: [], roomSettings: null }));
      this.emit({ type: "sessionEnded" });
    }
  }

  /** The extension's HTTP base: the name this connection reached (the LAN name at home), over https with wss. */
  private httpBase(): string {
    const tls = this.transport?.tls ?? this.config.useTls;
    const host = this._state.route?.host ?? this.config.host;
    return `${tls ? "https" : "http"}://${host.includes(":") && !host.startsWith("[") ? `[${host}]` : host}:${this.config.port}`;
  }

  /** The server's LAN name, for the next connection's race, when its certificate is the one we connect to. */
  private rememberLanName() {
    const tls = this._state.yarmiplay.tls;
    if (!tls?.lanName) return;
    if (tls.name.toLowerCase() === this.config.host.trim().toLowerCase() && tls.lanName !== this.config.lanName) {
      this.config = { ...this.config, lanName: tls.lanName };
    }
  }

  private sendOfferIfNeeded() {
    if (!this.logged || !this._session || !this._state.yarmiplay.capabilities.fileRelay) return;
    const files = this.offer;
    if (sameOffers(files, this.offerSent) || (files.length === 0 && this.offerSent === null)) return;
    this.sendYarmiplay({ offer: { files: files.map((f) => ({ name: f.name, size: f.size, duration: f.duration, quickHash: f.quickHash })) } });
    this.offerSent = files;
  }

  /** The one way out for `Yarmiplay` commands: never to a server that hasn't shown it's YarmiplayServerTV. */
  private sendYarmiplay(body: Json) {
    if (!this.config.deviceAuth || this._state.yarmiplay.serverKind !== "YARMIPLAY") return;
    this.send({ Yarmiplay: body });
  }

  private updateExt(change: (e: YarmiplayInfo) => YarmiplayInfo) {
    this.update({ yarmiplay: change(this._state.yarmiplay) });
  }

  // --- Playlist ----------------------------------------------------------------------

  private handlePlaylistChange(change: Json) {
    if (!Array.isArray(change.files)) return;
    const files = change.files.filter((f): f is string => typeof f === "string");
    this.applyPlaylist(files, str(change, "user"));
  }

  private applyPlaylist(files: string[], setBy: string | null) {
    const joining = this.awaitingRoomPlaylist;
    this.awaitingRoomPlaylist = false;
    const by = joining ? null : setBy;
    const previous = this._state.playlist;
    // The room's list is announced even when it's empty, so listeners know it has arrived.
    if (sameList(files, previous) && !joining) return;
    this.update({ playlist: files, playlistReceived: this._state.playlistReceived || joining });
    this.emit({ type: "playlistChanged", files, setBy: by, previous });
    if (by !== null && by !== this._state.username) this.notify(`${by} changed the playlist`);
  }

  private handlePlaylistIndex(indexObj: Json) {
    const by = str(indexObj, "user");
    const resetPosition = this.hadFirstPlaylistIndex;
    this.hadFirstPlaylistIndex = true;
    if (typeof indexObj.index !== "number") return;
    const index = indexObj.index;
    const files = this._state.playlist;
    if (files.length === 0) return;
    this.lastPlaylistIndexChange = this.clock();
    this.update({ playlistIndex: index });
    if (!this.engine.settings.sharedPlaylists) return;
    const filename = files[index];
    if (filename === undefined) return;
    const current = this.currentFile;
    const queued = this.queuedFilename;
    if (current && Filenames.same(filename, current.name) && (queued === null || Filenames.same(queued, filename))) return;
    if (by !== null && by !== this._state.username) this.notify(`${by} selected '${filename}'`);
    this.queuedFilename = filename;
    this.emit({ type: "switchToPlaylistItem", index, filename, setBy: by, resetPosition });
  }

  private changePlaylist(files: string[]) {
    const old = this._state.playlist;
    if (sameList(old, files)) return;
    const newIndex = validIndexFromNewPlaylist(old, this._state.playlistIndex, files);
    this.update({ playlist: files });
    if (!this.logged) return;
    this.sendSet({ playlistChange: { files } });
    if (files.length > 0) this.sendPlaylistIndex(newIndex);
  }

  private sendPlaylistIndex(index: number) {
    if (this.logged) this.sendSet({ playlistIndex: { index } });
  }

  private changeToPlaylistIndexFromFilename(filename: string) {
    if (!this.engine.settings.sharedPlaylists) return;
    const index = this._state.playlist.indexOf(filename);
    if (index >= 0 && index !== this._state.playlistIndex) this.sendPlaylistIndex(index);
  }

  private advancePlaylistCheck() {
    const s = this._state;
    const index = s.playlistIndex;
    const current = this.currentFile;
    if (index === null || !current) return;
    if (this.clock() - this.lastPlaylistIndexChange <= Constants.PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD) return;
    const entry = s.playlist[index];
    if (entry === undefined || !Filenames.same(entry, current.name)) return;
    const settings = this.engine.settings;
    if (!settings.sharedPlaylists) return;
    const next = nextPlaylistIndex(s.playlist.length, index, settings.loopPlaylist, settings.loopSingleFile);
    if (next === null) return;
    this.lastPlaylistIndexChange = this.clock();
    if (next === index) {
      // Not marked as an advance: the engine then sends the rewind and unpause to the room as a seek and play. Like
      // Syncplay, unpause again shortly after, because the room's echo of the end-of-file pause can re-pause it.
      this.player.seek(0);
      this.player.setPaused(false);
      setTimeout(() => this.player.setPaused(false), 500);
      return;
    }
    this.engine.markAdvanced();
    const filename = s.playlist[next];
    this.queuedFilename = filename;
    this.emit({ type: "advancePlaylist", nextIndex: next, filename });
  }

  // --- Outgoing messages ---------------------------------------------------------------

  private sendHello() {
    this.awaitingRoomPlaylist = true;
    this.update({ playlistReceived: false });
    const features: Json = {
      sharedPlaylists: true,
      chat: true,
      uiMode: "GUI",
      featureList: true,
      readiness: true,
      managedRooms: true,
      persistentRooms: true,
      setOthersReadiness: true,
    };
    if (this.config.deviceAuth) {
      features.yarmiplay = {
        protocol: Yarmiplay.PROTOCOL,
        client: "YarmiplayTV Web",
        ...(this.config.appVersion ? { version: this.config.appVersion } : {}),
      };
    }
    const hello: Json = { username: this.config.username };
    if (this.config.password) hello.password = md5Hex(this.config.password);
    Object.assign(hello, {
      room: { name: this.config.room },
      version: Constants.CLIENT_VERSION,
      realversion: Constants.REAL_VERSION,
      features,
    });
    this.send({ Hello: hello });
  }

  private sendFile() {
    const file = this.currentFile;
    if (!file || !this.logged) return;
    this.sendSet({ file: { duration: file.duration, name: file.name, size: file.size } });
    this.sendList();
  }

  private sendList() {
    if (this.logged) this.send({ List: null });
  }

  private changeReadyState(ready: boolean, manuallyInitiated: boolean) {
    this.myReady = ready;
    this.update({ isReady: ready });
    const me = this.users.get(this._state.username);
    if (me) this.users.set(me.name, { ...me, isReady: ready });
    this.publishUsers();
    this.sendReady(ready, manuallyInitiated);
  }

  private sendReady(ready: boolean, manuallyInitiated: boolean) {
    if (this.logged) this.sendSet({ ready: { isReady: ready, manuallyInitiated } });
  }

  private sendState(position: number | null, paused: boolean | null, doSeek: boolean, latencyCalculation: number | null, stateChange: boolean) {
    if (!this.logged) return;
    const clientIgnoreIsNotSet = this.clientIgnoringOnTheFly === 0 || this.serverIgnoringOnTheFly !== 0;
    if (stateChange) this.clientIgnoringOnTheFly += 1;
    const state: Json = {};
    if (clientIgnoreIsNotSet && position !== null && paused !== null) {
      state.playstate = { position, paused, ...(doSeek ? { doSeek: true } : {}) };
    }
    state.ping = {
      ...(latencyCalculation !== null && latencyCalculation !== 0 ? { latencyCalculation } : {}),
      clientLatencyCalculation: this.ping.newTimestamp(),
      clientRtt: this.ping.getRtt(),
    };
    if (this.serverIgnoringOnTheFly !== 0 || this.clientIgnoringOnTheFly !== 0) {
      state.ignoringOnTheFly = {
        ...(this.serverIgnoringOnTheFly !== 0 ? { server: this.serverIgnoringOnTheFly } : {}),
        ...(this.clientIgnoringOnTheFly !== 0 ? { client: this.clientIgnoringOnTheFly } : {}),
      };
      this.serverIgnoringOnTheFly = 0;
    }
    this.send({ State: state });
  }

  private sendSet(body: Json) {
    this.send({ Set: body });
  }

  private send(message: Json) {
    const line = JSON.stringify(message);
    this.log(`>> ${redact(line)}`);
    this.transport?.send(line);
  }

  // --- Helpers ---------------------------------------------------------------------------

  private publishUsers() {
    const me = this._state.username;
    const sorted = [...this.users.values()].sort((a, b) => {
      if ((a.name === me) !== (b.name === me)) return a.name === me ? -1 : 1;
      const x = a.name.toLowerCase();
      const y = b.name.toLowerCase();
      return x < y ? -1 : x > y ? 1 : 0;
    });
    this.update({ users: sorted });
  }

  private notify(message: string) {
    this.emit({ type: "notification", message, isError: false });
  }

  private emit(event: SyncplayEvent) {
    for (const listener of [...this.eventListeners]) listener(event);
  }

  private update(change: Partial<RoomState>) {
    this._state = { ...this._state, ...change };
    for (const listener of [...this.stateListeners]) listener(this._state);
  }

  private engineHost(): SyncHost {
    const client = this;
    return {
      get username() {
        return client._state.username;
      },
      get readinessSupported() {
        return client._state.serverFeatures.readiness !== false;
      },
      get controlsPlayback() {
        return !followsOperators(client._state);
      },
      get isReady() {
        return client.myReady;
      },
      get currentFileDuration() {
        return client.currentFile?.duration ?? 0;
      },
      areAllOtherUsersReady: () =>
        [...client.users.values()].filter((u) => u.name !== client._state.username && u.file !== null).every((u) => u.isReady !== false),
      notJustChangedPlaylist: () =>
        client.clock() - client.lastPlaylistIndexChange > Constants.PLAYLIST_LOAD_NEXT_FILE_TIME_FROM_END_THRESHOLD,
      sendState: (position, paused, doSeek, latency, stateChange) => client.sendState(position, paused, doSeek, latency, stateChange),
      changeReadyState: (ready, manually) => client.changeReadyState(ready, manually),
      notify: (message) => client.notify(message),
      advancePlaylistCheck: () => client.advancePlaylistCheck(),
    };
  }
}

const user = (name: string, room: string, file: FileInfo | null, isReady: boolean | null): RoomUser => ({
  name,
  room,
  file,
  isReady,
  isController: false,
  yarmiplay: false,
});

const sameFile = (a: FileInfo | null, b: FileInfo | null) =>
  a === b || (a !== null && b !== null && a.name === b.name && a.duration === b.duration && a.size === b.size && (a.sizeHash ?? null) === (b.sizeHash ?? null));

const sameList = (a: string[], b: string[]) => a.length === b.length && a.every((x, i) => x === b[i]);

const sameOffers = (a: RelayOffer[], b: RelayOffer[] | null) =>
  b !== null &&
  a.length === b.length &&
  a.every((x, i) => x.name === b[i].name && x.size === b[i].size && x.duration === b[i].duration && x.quickHash === b[i].quickHash);

/** Port of Syncplay's _thereIsNextPlaylistIndex/_nextPlaylistIndex. */
export function nextPlaylistIndex(size: number, index: number, loopPlaylist: boolean, loopSingleFile: boolean): number | null {
  if (size === 1) return loopSingleFile ? index : null;
  if (index + 1 < size) return index + 1;
  return loopPlaylist ? 0 : null;
}

/** Port of Syncplay's _getValidIndexFromNewPlaylist: keep pointing at the same file if it survived the edit. */
export function validIndexFromNewPlaylist(old: string[], oldIndex: number | null, files: string[]): number {
  if (oldIndex === null || files.length <= 1) return 0;
  for (let i = oldIndex; i <= old.length; i++) {
    const name = old[i];
    if (name !== undefined && files.indexOf(name) >= 0) return files.indexOf(name);
  }
  for (let i = oldIndex; i > 0; i--) {
    const name = old[i];
    if (name === undefined) continue;
    const at = files.indexOf(name);
    if (at >= 0) return at < files.length - 1 ? at + 1 : at;
  }
  return 0;
}

export function parseFile(element: unknown): FileInfo | null {
  if (!isObject(element) || Object.keys(element).length === 0) return null;
  const name = str(element, "name");
  if (name === null) return null;
  const duration = typeof element.duration === "number" ? element.duration : 0;
  const sizeValue = element.size;
  const sizeHash = typeof sizeValue === "string" && Filenames.isHash(sizeValue) ? sizeValue : null;
  const size = sizeHash !== null ? 0 : typeof sizeValue === "number" ? Math.trunc(sizeValue) : 0;
  return { name, duration, size, sizeHash };
}

export function parseCapabilities(element: unknown): Capabilities | null {
  if (!isObject(element)) return null;
  return {
    ...noCapabilities(),
    fileRelay: bool(element, "fileRelay"),
    jellyfin: bool(element, "jellyfin"),
    https: bool(element, "https"),
    prefetch: bool(element, "prefetch"),
    roomSettings: bool(element, "roomSettings"),
    websocket: bool(element, "websocket"),
    mal: bool(element, "mal"),
  };
}

/** `{"name": "...", "lan": ["ip:port", ...], "lanName": "..."}`, or null for JSON null or one without a name. */
export function parseTls(element: unknown): ServerTls | null {
  if (!isObject(element)) return null;
  const name = str(element, "name")?.trim();
  if (!name) return null;
  const lan = Array.isArray(element.lan) ? element.lan.filter((a): a is string => typeof a === "string" && a.trim() !== "").map((a) => a.trim()) : [];
  const lanName = str(element, "lanName")?.trim() || null;
  return { name, lan, lanName };
}

const parseSharedJellyfin = (o: Json): SharedJellyfin => ({
  available: bool(o, "available"),
  serverId: str(o, "serverId") ?? "",
  serverName: str(o, "serverName") ?? "",
  proxy: bool(o, "proxy"),
  addresses: Array.isArray(o.addresses) ? o.addresses.filter((a): a is string => typeof a === "string") : [],
});

function parseRelayFile(element: unknown): RelayFile | null {
  if (!isObject(element)) return null;
  const id = str(element, "id");
  const name = str(element, "name");
  if (!id || !name) return null;
  return {
    id,
    name,
    size: num(element, "size"),
    duration: num(element, "duration"),
    quickHash: str(element, "quickHash") ?? "",
    sources: Math.trunc(num(element, "sources")),
    cachedBytes: num(element, "cachedBytes"),
    rate: num(element, "rate"),
  };
}

function parseUpload(o: Json): UploadRequest | null {
  const id = str(o, "id");
  const length = num(o, "length");
  if (!id || length <= 0) return null;
  return { id, file: str(o, "file") ?? "", size: num(o, "size"), quickHash: str(o, "quickHash") ?? "", offset: num(o, "offset"), length };
}

/** [line] for the log, without session tokens. */
export const redact = (line: string): string => line.replace(/("token"\s*:\s*")[^"]*"/g, '$1…"');
