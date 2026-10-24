import * as Settings from "../data/settings";
import * as SavedRooms from "../data/savedRooms";
import { getSeekPosition } from "../data/seekPositions";
import type { LocalFile, LocalLibrary } from "../local/library";
import { MediaLocator, fileNameOf, isPlayable, ownCopy, type EpisodeInfo, type MediaItem, type MediaSource, type PlayableMedia, type RoomLocation, type ServerCopy } from "../media";
import * as FileNames from "../media/fileNames";
import { preferredSidecar, rememberSeek, sidecarLanguage, type ExternalSubtitle } from "../media/seekThumbs";
import { findRelayFile, openRelayFile, RelaySharing } from "../relay/relay";
import type { RelayFile } from "../syncplay/yarmiplay";
import { FileSource, HttpSource, type ByteSource } from "../player/bytes";
import type { MediaPlayer } from "../player/mediaPlayer";
import { Store } from "../store";
import {
  SyncplayClient,
  followsOperators,
  roomSettingsShared,
  hasManagedRooms,
  nextPlaylistIndex,
  type RoomState,
  type SyncplayConfig,
  type SyncplayEvent,
} from "../syncplay/client";
import * as Filenames from "../syncplay/filenames";
import * as ManagedRooms from "../syncplay/managedRooms";
import type { FileInfo } from "../syncplay/models";
import { isLoopbackHost } from "../syncplay/transport";
import type { DeviceAuth } from "../syncplay/yarmiplay";
import * as PlaylistEdits from "./playlistEdits";
import { TrustedDomains } from "./playlistEdits";
import { VoiceController } from "../voice/controller";

/** A line in the chat and notification feed. */
export interface FeedMessage {
  text: string;
  from: string | null;
  isError: boolean;
  at: number;
}

export type PlaylistStatus =
  | { kind: "idle" }
  | { kind: "resolving"; fileName: string }
  | { kind: "loading"; fileName: string }
  | { kind: "notFound"; fileName: string; reason: string }
  | { kind: "failed"; fileName: string; reason: string }
  /** The room picked a URL that isn't on a trusted domain; it only opens when the user says so. */
  | { kind: "untrusted"; url: string; domain: string | null };

/** What the player is (about to be) showing (PlaylistController.kt's NowPlaying). */
export interface NowPlaying {
  title: string;
  /** The name the room knows it by: the file's name, or the URL. */
  fileName: string;
  size: number;
  /** Identifies this playback (the stream's URL, or the file's place here); reports and skipping key on it. */
  key: string;
  /** The duration the server gave, until the player knows its own. */
  durationHint: number;
  /** Server items that are this file (whichever copy is streamed); playback is reported to each. */
  copies: ServerCopy[];
  /** Which episode of which show this is, from the server or the file name; null for movies and unknown files. */
  episode: EpisodeInfo | null;
  /** The media server item streamed, for its skip markers. */
  item: { sourceKey: string; itemId: string } | null;
  /** The file on this device, when it's one (listening reads its neighbours). */
  local: LocalFile | null;
  /** Read over the network (listening to other episodes waits for an unmetered network). */
  remote: boolean;
  open(): Promise<ByteSource>;
}

function subtitleFileName(sub: ExternalSubtitle): string {
  const fromUrl = decodeURIComponent(sub.url.split("?")[0].split("/").pop() || "");
  if (/\.(srt|ass|ssa|vtt)$/i.test(fromUrl)) return fromUrl;
  if (/\.(srt|ass|ssa|vtt)$/i.test(sub.title)) return sub.title;
  return `${sub.title}.srt`;
}

/** The episode a file is, from its name (and folder, e.g. "Show/Season 2"), for files no server describes. */
export function episodeFromFile(fileName: string, directory: string | null = null): EpisodeInfo | null {
  const p = FileNames.parseEpisode(fileName, directory);
  if (!p) return null;
  return { seriesName: p.title, season: p.season, episode: p.episode, year: p.year, seriesKey: `file/${FileNames.normalize(p.title)}`, malId: null };
}

/** A media server's stream as something to play. */
export function playableNowPlaying(playable: PlayableMedia, copies: ServerCopy[] = ownCopy(playable)): NowPlaying {
  return {
    title: playable.title || playable.fileName,
    fileName: playable.fileName,
    size: playable.sizeBytes,
    key: playable.url,
    durationHint: playable.durationSeconds,
    copies,
    episode: playable.episode ?? episodeFromFile(playable.fileName),
    item: playable.sourceKey ? { sourceKey: playable.sourceKey, itemId: playable.itemId } : null,
    local: null,
    remote: true,
    open: () => HttpSource.open(playable.url, { name: playable.fileName, size: playable.sizeBytes }),
  };
}

/** Where the next entry was found ahead of time; used when the room gets to it. */
interface Prepared {
  fileName: string;
  location: RoomLocation<RelayFile>;
  at: number;
}

/** Lets the selected entry start opening before the next one is looked up. */
const PREPARE_DELAY_MS = 1_500;
/** A lookup this old is done again: the servers' libraries may have changed. */
const PREPARED_MAX_AGE_MS = 30 * 60 * 1000;

const MAX_FEED = 200;
const MAX_UNDO = 50;
/** Syncplay's MAX_CHAT_MESSAGE_LENGTH. */
export const DEFAULT_MAX_CHAT_LENGTH = 150;

const emptyRoom = (): RoomState => new SyncplayClient({ host: "", port: 0, username: "", room: "", useTls: true }, idlePlayer).state;

const idlePlayer = {
  isFileLoaded: false,
  duration: 0,
  position: 0,
  isPaused: true,
  setPaused() {},
  seek() {},
  setSpeed() {},
};

export interface JoinOptions {
  /** Ask the host to approve this device if it isn't yet (false for connections the user didn't start). */
  requestAccess?: boolean;
  /** Typed on the join form for a managed room. */
  operatorPassword?: string;
  /** Once joined, make a managed room of this name and move there as its operator. */
  createManagedRoom?: string;
  /** Save the room as soon as it's joined ("Save this room"), under this label. */
  saveAs?: string;
  lanName?: string | null;
}

/**
 * The room this browser is in (SyncController, PlaylistController, SharedPlaylist and PlaylistAutosave for the web):
 * owns the Syncplay client, plays what the room selects (found in this device's files, or a trusted link), keeps the
 * chat feed, the playlist's undo history, saved and recent rooms, and each room's playlist and operator password.
 */
export class RoomSession {
  readonly room = new Store<RoomState>(emptyRoom());
  readonly feed = new Store<FeedMessage[]>([]);
  readonly toasts = new Store<FeedMessage | null>(null);
  readonly blocked = new Store<Set<string>>(new Set());
  readonly status = new Store<PlaylistStatus>({ kind: "idle" });
  readonly nowPlaying = new Store<NowPlaying | null>(null);
  readonly canUndo = new Store(false);
  readonly connectionError = new Store<string | null>(null);
  /** Browser room voice (`/yarmiplay/voice`); inactive when the server has no voice. */
  readonly voice = new VoiceController(() => this.client);

  private client: SyncplayClient | null = null;
  private unsubscribe: (() => void)[] = [];
  private server: string | null = null;
  private lastJoin: { profile: Settings.ConnectionProfile; options: JoinOptions } | null = null;
  private reportedFile: FileInfo | null = null;
  private loadToken = 0;

  /** Files picked here by name, so the room's echo of a pick needs no lookup. */
  private readonly known = new Map<string, NowPlaying>();

  // Shared playlist history (SharedPlaylist.kt).
  private history: string[][] = [];
  private sent: string[] | null = null;

  // Playlist autosave: the room whose playlist has arrived since we last joined.
  private joinedKey: string | null = null;
  private pendingCreate: string | null = null;
  private saveNextAs: string | null = null;
  private readonly relay: RelaySharing;
  readonly locator: MediaLocator<RelayFile>;
  private prepared: Prepared | null = null;
  private preparing: { fileName: string; lookup: Promise<RoomLocation<RelayFile>> } | null = null;
  private prepareTimer: ReturnType<typeof setTimeout> | undefined;
  private previousMedia: NowPlaying | null = null;
  private autoPlayTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly player: MediaPlayer,
    private readonly library: LocalLibrary,
    private settings: Settings.Settings,
    private readonly deviceAuth: DeviceAuth | null,
    private readonly appVersion: string,
    private readonly onSettingsChanged: (s: Settings.Settings) => void = () => {},
    private readonly servers: () => readonly MediaSource[] = () => [],
  ) {
    this.relay = new RelaySharing(() => this.client, library, () => this.settings.shareFiles, () => this.reportedFile);
    library.subscribe(() => this.relay.refresh());
    this.locator = new MediaLocator<RelayFile>(library, servers, (name) => findRelayFile(this.room.value, name));
    this.locator.preferredServer = settings.preferredServer;
  }

  get isActive(): boolean {
    return this.client !== null && this.room.value.status !== "DISCONNECTED";
  }

  get syncClient(): SyncplayClient | null {
    return this.client;
  }

  /** The room's server as it was entered, without its port; null outside a room. */
  get serverHost(): string | null {
    return this.server?.replace(/:\d+$/, "") ?? null;
  }

  updateSettings(settings: Settings.Settings) {
    const sharingChanged = settings.shareFiles !== this.settings.shareFiles;
    this.settings = settings;
    if (this.client) this.client.settings = settings.sync;
    this.locator.preferredServer = settings.preferredServer;
    if (sharingChanged) this.relay.refresh();
  }

  /** The server's chat message limit (it cuts longer messages off), or Syncplay's default before joining. */
  get maxChatLength(): number {
    const n = this.room.value.serverFeatures.maxChatMessageLength;
    return typeof n === "number" && n > 0 ? n : DEFAULT_MAX_CHAT_LENGTH;
  }

  // --- Joining -----------------------------------------------------------------------------

  join(profile: Settings.ConnectionProfile, options: JoinOptions = {}) {
    this.disconnect();
    this.lastJoin = { profile, options };
    this.server = Settings.serverKey(profile.host, profile.port);
    const room = profile.room.trim();
    const saved = this.settings.savedRooms.find((r) => r.host.toLowerCase() === profile.host.trim().toLowerCase() && r.port === profile.port);
    const operatorPassword = options.operatorPassword ? ManagedRooms.normalizePassword(options.operatorPassword) : "";
    if (operatorPassword && ManagedRooms.isManaged(room)) Settings.saveRoomPassword(`${this.server}/${room}`, operatorPassword);

    const local = isLoopbackHost(profile.host);
    const config: SyncplayConfig = {
      host: profile.host.trim(),
      port: profile.port,
      username: profile.username.trim(),
      room,
      password: profile.password || null,
      // wss:// always, but for a server on this computer.
      useTls: !local,
      deviceAuth: this.deviceAuth,
      appVersion: this.appVersion,
      requestAccess: options.requestAccess ?? true,
      lanName: options.lanName ?? saved?.lanName ?? null,
    };
    const client = new SyncplayClient(config, this.player, this.settings.sync);
    client.savedOperatorPassword = (r) => Settings.roomPassword(`${this.server}/${r}`);
    this.client = client;
    this.pendingCreate = options.createManagedRoom?.trim() || null;
    this.saveNextAs = options.saveAs ?? null;
    this.unsubscribe = [client.subscribe((s) => this.onRoomState(s)), client.onEvent((e) => this.onEvent(e))];
    client.start();
    this.voice.start();
    if (this.reportedFile) client.fileLoaded(this.reportedFile, false);
  }

  /** Joins again with the last join's settings, e.g. to ask a YarmiplayServerTV host for access. */
  rejoin(requestAccess = true) {
    if (this.lastJoin) this.join(this.lastJoin.profile, { ...this.lastJoin.options, requestAccess, createManagedRoom: undefined });
  }

  disconnect() {
    this.voice.stop();
    this.relay.cancelAll();
    this.unsubscribe.forEach((u) => u());
    this.unsubscribe = [];
    this.client?.close();
    this.client = null;
    this.room.set(emptyRoom());
    this.connectionError.set(null);
    this.blocked.set(new Set());
    this.history = [];
    this.sent = null;
    this.canUndo.set(false);
    this.joinedKey = null;
    this.player.setSpeed(1);
  }

  changeRoom(room: string) {
    this.blocked.set(new Set());
    this.client?.changeRoom(room.trim());
  }

  // --- Room events ----------------------------------------------------------------------------

  private onRoomState(state: RoomState) {
    const before = this.room.value;
    this.room.set(state);
    if (state.playlist !== before.playlist && this.sent && PlaylistEdits.format(state.playlist) === PlaylistEdits.format(this.sent)) this.sent = null;
    if (state.status !== before.status || state.room !== before.room) {
      if (state.status !== "CONNECTED" || state.room !== before.room) this.clearHistory();
    }
    this.autosave(state);
    if (
      state.playlist !== before.playlist ||
      state.room !== before.room ||
      state.yarmiplay.session !== before.yarmiplay.session ||
      state.yarmiplay.capabilities.fileRelay !== before.yarmiplay.capabilities.fileRelay ||
      state.users !== before.users
    ) {
      this.relay.refresh();
    }
    // A viewer who has the room's file joining (or sharing it) makes "not found" playable.
    const missing = this.status.value;
    if (missing.kind === "notFound" && state.yarmiplay.relayFiles !== before.yarmiplay.relayFiles) {
      const current = state.playlistIndex === null ? null : state.playlist[state.playlistIndex];
      if (current === missing.fileName && findRelayFile(state, current)) void this.loadFromRoom(current, false);
    }
    // If we are playing a smaller (incomplete) local or server copy and a peer offers a larger completed copy on the relay,
    // switch to streaming the completed copy from the relay.
    if (state.yarmiplay.relayFiles !== before.yarmiplay.relayFiles || state.users !== before.users) {
      const playing = this.nowPlaying.value;
      if (playing && !playing.key.startsWith("relay:") && !Filenames.isUrl(playing.fileName) && playing.size > 0) {
        const relayed = findRelayFile(state, playing.fileName);
        if (relayed && relayed.size > playing.size) {
          for (const k of [...this.known.keys()]) {
            if (Filenames.same(k, playing.fileName)) this.known.delete(k);
          }
          void this.loadFromRoom(playing.fileName, false);
        }
      }
    }
  }

  private onEvent(event: SyncplayEvent) {
    switch (event.type) {
      case "chat":
        if (this.settings.showChat && !this.blocked.value.has(event.username)) this.post({ text: event.message, from: event.username });
        break;
      case "notification":
        this.post({ text: event.message, isError: event.isError });
        break;
      case "connected": {
        this.connectionError.set(null);
        this.post({ text: `Joined room '${ManagedRooms.label(event.room)}' as ${event.username}` });
        if (event.motd) this.post({ text: event.motd.trim() });
        this.recordVisit(event.room);
        const create = this.pendingCreate;
        this.pendingCreate = null;
        if (create && !this.client?.createManagedRoom(create)) {
          const why = hasManagedRooms(this.room.value) ? "the name doesn't fit" : "this server doesn't have managed rooms";
          this.post({ text: `Couldn't create managed room '${create}': ${why}`, isError: true });
        }
        break;
      }
      case "managedRoomCreated":
        Settings.saveRoomPassword(`${this.server}/${event.room}`, event.password);
        if (this.lastJoin) this.lastJoin = { ...this.lastJoin, profile: { ...this.lastJoin.profile, room: event.room } };
        this.post({ text: `Created managed room '${ManagedRooms.display(event.room)}' (${event.room}). Operator password: ${event.password}` });
        this.recordVisit(event.room);
        break;
      case "controllerAuth": {
        const mine = event.user === this.room.value.username;
        const key = `${this.server}/${event.room}`;
        if (mine && event.success) {
          if (event.password) Settings.saveRoomPassword(key, event.password);
          this.post({ text: "You are now a room operator" });
        } else if (mine) {
          Settings.saveRoomPassword(key, null);
          this.post({ text: "Wrong room password", isError: true });
        } else if (event.success && event.room === this.room.value.room) {
          this.post({ text: `${event.user} is now a room operator` });
        }
        break;
      }
      case "readySetBy": {
        const whom = event.user === this.room.value.username ? "you" : event.user;
        this.post({ text: `${event.setBy} marked ${whom} ${event.isReady ? "ready" : "not ready"}` });
        break;
      }
      case "disconnected":
        this.connectionError.set(event.reason ?? "Connection closed");
        this.post({
          text: `Disconnected: ${event.reason ?? "unknown"}${event.willReconnect ? " — reconnecting" : ""}`,
          isError: !event.willReconnect,
        });
        break;
      case "playlistChanged":
        // The list sent on joining has no user; our own edits are recorded when we make them.
        if (event.setBy !== null && event.setBy !== this.room.value.username) {
          this.sent = null;
          this.record(event.previous);
        }
        break;
      case "switchToPlaylistItem":
        void this.loadFromRoom(event.filename, event.resetPosition);
        break;
      case "advancePlaylist":
        void this.loadFromRoom(event.filename, true);
        break;
      case "uploadRequested":
        void this.relay.upload(event.request, this.client?.session ?? null);
        break;
      case "uploadCancelled":
        this.relay.cancel(event.id);
        break;
      case "sessionEnded":
        this.relay.cancelAll();
        break;
      case "roomSettingsChanged": {
        const me = this.room.value.username;
        if (event.first) {
          // A saved room this device last had it on in turns it on again (never off: someone else may want it).
          const entry = this.currentRoomEntry();
          if (entry?.saved && entry.autoSkip && !event.settings.autoSkip) this.client?.setRoomAutoSkip(true);
        } else if (event.settings.setBy && event.settings.setBy !== me) {
          this.post({ text: `${event.settings.setBy} turned ${event.settings.autoSkip ? "on" : "off"} skipping openings and endings` });
        }
        break;
      }
    }
  }

  // --- Saved and recent rooms; each room's playlist ----------------------------------------------

  private recordVisit(room: string) {
    const join = this.lastJoin;
    if (!join) return;
    const lanName = this.client?.config.lanName ?? null;
    const visit = SavedRooms.room({
      host: join.profile.host.trim(),
      port: join.profile.port,
      username: this.room.value.username || join.profile.username.trim(),
      room,
      password: join.profile.password,
      lastUsedMillis: Date.now(),
      ...(lanName ? { lanName } : {}),
    });
    let rooms = SavedRooms.visited(this.settings.savedRooms, visit);
    if (this.saveNextAs !== null) {
      rooms = SavedRooms.setSaved(rooms, SavedRooms.keyOf(visit), true, this.saveNextAs);
      this.saveNextAs = null;
    }
    this.changeSettings({
      ...this.settings,
      savedRooms: rooms,
      connection: { ...join.profile, room },
    });
  }

  private changeSettings(settings: Settings.Settings) {
    this.settings = settings;
    Settings.save(settings);
    this.onSettingsChanged(settings);
  }

  private autosave(state: RoomState) {
    const inRoom = state.status === "CONNECTED" && state.playlistReceived;
    const key = this.server && state.room ? `${this.server}/${state.room}` : null;
    if (!inRoom || !key) {
      this.joinedKey = null;
      return;
    }
    if (key !== this.joinedKey) {
      this.joinedKey = key;
      if (!this.settings.autosavePlaylists) return;
      if (state.playlist.length > 0) {
        Settings.saveRoomPlaylist(key, state.playlist);
        return;
      }
      const saved = Settings.roomPlaylist(key);
      if (saved.length === 0) return;
      this.replacePlaylist(saved);
      this.post({ text: `Restored this room's playlist (${saved.length} ${saved.length === 1 ? "entry" : "entries"})` });
    } else if (this.settings.autosavePlaylists) {
      Settings.saveRoomPlaylist(key, state.playlist);
    }
  }

  // --- Feed --------------------------------------------------------------------------------

  private post(message: Partial<FeedMessage> & { text: string }) {
    const full: FeedMessage = { from: null, isError: false, at: Date.now(), ...message };
    this.feed.update((f) => [...f, full].slice(-MAX_FEED));
    this.toasts.set(full);
  }

  postLocal(text: string, isError = false) {
    this.post({ text, isError });
  }

  sendChat(text: string) {
    this.client?.sendChat(text.trim().slice(0, this.maxChatLength));
  }

  /** Hides [name]'s chat, also the lines already shown, until the room is left. */
  block(name: string) {
    this.blocked.update((b) => new Set([...b, name]));
    this.feed.update((f) => f.filter((m) => m.from !== name));
  }

  unblock(name: string) {
    this.blocked.update((b) => new Set([...b].filter((n) => n !== name)));
  }

  setShowChat(show: boolean) {
    if (!show) this.feed.update((f) => f.filter((m) => m.from === null));
  }

  toggleReady() {
    this.client?.toggleReady();
  }

  // --- Skipping openings and endings in rooms (RoomAutoSkip.kt) ---------------------------------

  /** The saved or recent room this connection is in. */
  private currentRoomEntry(): SavedRooms.SavedRoom | null {
    const join = this.lastJoin;
    const room = this.room.value.room;
    if (!join || !room) return null;
    const key = SavedRooms.keyOf({ host: join.profile.host.trim(), port: join.profile.port, room });
    return this.settings.savedRooms.find((r) => SavedRooms.keyOf(r) === key) ?? null;
  }

  /**
   * The room's "skip openings and endings": on YarmiplayServerTV the room's own setting, the same for everyone (in a
   * managed room only operators change it); elsewhere this device's choice, remembered with the room.
   */
  roomAutoSkip(): { inRoom: boolean; on: boolean; shared: boolean; setBy: string | null; canChange: boolean } {
    const room = this.room.value;
    if (room.status !== "CONNECTED" || !room.room) return { inRoom: false, on: false, shared: false, setBy: null, canChange: false };
    const shared = roomSettingsShared(room.yarmiplay);
    const settings = room.yarmiplay.roomSettings;
    return {
      inRoom: true,
      on: shared ? settings?.autoSkip === true : this.currentRoomEntry()?.autoSkip === true,
      shared,
      setBy: shared ? (settings?.setBy ?? null) : null,
      canChange: !followsOperators(room),
    };
  }

  /** Turns it on or off: for the whole room when shared, else for this device in this room. */
  setRoomAutoSkip(on: boolean) {
    const state = this.roomAutoSkip();
    if (!state.inRoom || !state.canChange) return;
    if (state.shared) this.client?.setRoomAutoSkip(on);
    const entry = this.currentRoomEntry();
    if (entry) this.changeSettings({ ...this.settings, savedRooms: SavedRooms.setAutoSkip(this.settings.savedRooms, SavedRooms.keyOf(entry), on) });
    this.room.set({ ...this.room.value });
  }

  setOthersReady(username: string, isReady: boolean) {
    this.client?.setOthersReady(username, isReady);
  }

  createManagedRoom(baseName: string): boolean {
    return this.client?.createManagedRoom(baseName) === true;
  }

  identifyAsOperator(password: string) {
    this.client?.identifyAsOperator(password);
  }

  /** The saved operator password for [room] on the current server, to show it to its operator. */
  savedOperatorPassword(room: string): string | null {
    return this.server ? Settings.roomPassword(`${this.server}/${room}`) : null;
  }

  // --- Playing ---------------------------------------------------------------------------------

  /** Whether this device can play a playlist entry without asking: a link, a file opened here, or one in the folders. */
  isAvailable(fileName: string): boolean {
    return Filenames.isUrl(fileName) || [...this.known.keys()].some((k) => Filenames.same(k, fileName)) || this.library.find(fileName) !== null;
  }

  private async loadFromRoom(filename: string, resetPosition: boolean) {
    if (Filenames.isUrl(filename)) {
      if (this.settings.onlyTrustedDomains && !TrustedDomains.isTrusted(filename, this.settings.trustedDomains)) {
        this.status.set({ kind: "untrusted", url: filename, domain: TrustedDomains.domainOf(filename) });
        this.postLocal(`Not opening ${filename}: it isn't on a trusted domain`, true);
        return;
      }
      await this.load(urlNowPlaying(filename), resetPosition, true);
      return;
    }
    const knownEntry = [...this.known.entries()].find(([k]) => Filenames.same(k, filename));
    const relayedNow = findRelayFile(this.room.value, filename);
    if (knownEntry) {
      const [knownKey, known] = knownEntry;
      if (relayedNow && known.size > 0 && relayedNow.size > known.size) {
        this.known.delete(knownKey);
      } else {
        await this.load(known, resetPosition, true);
        return;
      }
    }
    // Found ahead of time, or looked up now in the apps' order (MediaLocator): this device, the media servers, the relay.
    this.status.set({ kind: "resolving", fileName: filename });
    const ahead = this.takePrepared(filename);
    let found = await (ahead ?? this.locate(filename));
    // Missing ahead of time may be there now (e.g. a viewer who has it joined): look again.
    if (ahead && found.kind === "missing") found = await this.locate(filename);
    await this.play(found, filename, resetPosition);
  }

  private async locate(fileName: string): Promise<RoomLocation<RelayFile>> {
    try {
      return await this.locator.locateForRoom(fileName);
    } catch (e) {
      return { kind: "missing", reason: (e as Error).message || "Lookup failed" };
    }
  }

  private async play(found: RoomLocation<RelayFile>, filename: string, resetPosition: boolean) {
    switch (found.kind) {
      case "local":
        await this.load(localNowPlaying(found.file), resetPosition, true);
        return;
      case "server":
        await this.load(playableNowPlaying(found.playable, found.copies), resetPosition, true);
        return;
      case "relay": {
        const session = this.client?.session;
        const relayed = found.file;
        if (!session) break;
        await this.load(
          {
            title: relayed.name,
            fileName: relayed.name,
            size: relayed.size,
            key: `relay:${relayed.id}`,
            durationHint: relayed.duration,
            copies: [],
            episode: episodeFromFile(relayed.name),
            item: null,
            local: null,
            remote: true,
            open: () => openRelayFile(session, relayed),
          },
          resetPosition,
          true,
        );
        return;
      }
      case "missing":
        break;
    }
    const servers = this.servers();
    const where = [servers.length > 1 ? "your media servers" : servers[0]?.displayName, this.library.hasFolders ? "your media folders" : null]
      .filter(Boolean)
      .join(" or ");
    const relayOn = this.room.value.yarmiplay.session && this.room.value.yarmiplay.capabilities.fileRelay;
    const reason =
      found.kind === "missing" && found.reason
        ? found.reason
        : relayOn
          ? "Nobody in the room is sharing it yet"
          : "Add a media folder or a media server, or pick the file, to play the room's entries";
    this.status.set({ kind: "notFound", fileName: filename, reason });
    this.postLocal(where ? `'${filename}' not found in ${where}` : `Can't load '${filename}': ${reason.toLowerCase()}`, true);
  }

  /** The entry after the selected one, as the room advances to it; null when there's none (or a link, or this one). */
  private nextEntry(): string | null {
    const room = this.room.value;
    if (room.status !== "CONNECTED" || room.playlistIndex === null) return null;
    const s = this.settings.sync;
    const next = nextPlaylistIndex(room.playlist.length, room.playlistIndex, s.loopPlaylist, s.loopSingleFile);
    if (next === null || next === room.playlistIndex) return null;
    const name = room.playlist[next];
    return name && !Filenames.isUrl(name) ? name : null;
  }

  /** Looks up the playlist's next entry a moment after this one starts, so moving on starts at once. */
  private schedulePrepare() {
    clearTimeout(this.prepareTimer);
    this.prepareTimer = setTimeout(() => void this.prepareNext(), PREPARE_DELAY_MS);
  }

  private async prepareNext() {
    const name = this.nextEntry();
    if (!name || [...this.known.keys()].some((k) => Filenames.same(k, name))) return;
    if (!this.locator.hasServers && !this.library.hasFolders && !this.locator.hasRelay(name)) return;
    const prepared = this.prepared;
    const fresh = prepared && Filenames.same(prepared.fileName, name) && Date.now() - prepared.at < PREPARED_MAX_AGE_MS ? prepared : null;
    const relaySize = findRelayFile(this.room.value, name)?.size ?? 0;
    const freshSize =
      fresh?.location.kind === "local" ? fresh.location.file.size : fresh?.location.kind === "server" ? fresh.location.playable.sizeBytes : 0;
    // A relay entry can show up later (a viewer who has the file joins); found elsewhere it won't get better unless a larger relay copy arrived.
    if (fresh && fresh.location.kind !== "relay" && (freshSize <= 0 || relaySize <= freshSize)) return;
    const lookup = this.preparing && Filenames.same(this.preparing.fileName, name) ? this.preparing.lookup : this.locate(name);
    this.preparing = { fileName: name, lookup };
    const location = await lookup;
    if (this.preparing?.lookup === lookup) this.preparing = null;
    this.prepared = location.kind === "missing" ? null : { fileName: name, location, at: fresh?.at ?? Date.now() };
  }

  /** Seek-bar frames and subtitle files for this device only. Nothing here is sent to the room. */
  private async attachExtras(media: NowPlaying, token: number) {
    const source = media.item ? this.servers().find((s) => s.key === media.item?.sourceKey) : undefined;
    if (source?.storedSeekFrames && media.item) {
      const frames = await source.storedSeekFrames(media.item.itemId).catch(() => null);
      if (token !== this.loadToken) return;
      if (frames) rememberSeek(media.key, frames);
    }
    const subs = await this.discoverSubs(media, source);
    if (token !== this.loadToken || subs.length === 0) return;
    const pick = this.player.snapshot.subtitleTrack !== null ? null : preferredSidecar(subs, this.settings.playback.subtitleLanguages);
    for (const sub of subs) {
      if (token !== this.loadToken) return;
      const text = sub.text ?? (await fetch(sub.url).then((r) => (r.ok ? r.text() : null)).catch(() => null));
      if (!text) continue;
      await this.player.addExternalSubtitle(subtitleFileName(sub), text, sub.language, sub === pick);
    }
  }

  private async discoverSubs(media: NowPlaying, source: MediaSource | undefined): Promise<Array<ExternalSubtitle & { text?: string }>> {
    const subs: Array<ExternalSubtitle & { text?: string }> = [];
    if (media.local) {
      for (const file of this.library.sidecarsBeside(media.local)) {
        const text = await file.open().then((f) => f.text()).catch(() => null);
        if (text) subs.push({ url: `file:${file.folder}/${file.directory}/${file.name}`, title: file.name, language: sidecarLanguage(file.name), text });
      }
    }
    if (source?.externalSubtitles && media.item && media.item.itemId !== media.key) {
      const remote = await source.externalSubtitles(media.item.itemId).catch(() => []);
      for (const sub of remote) if (!subs.some((have) => have.url === sub.url)) subs.push(sub);
    }
    return subs;
  }

  /** What was found for [filename] ahead of time, or the lookup still under way; null when it wasn't prepared. */
  private takePrepared(filename: string): Promise<RoomLocation<RelayFile>> | null {
    const p = this.prepared;
    if (p && Filenames.same(p.fileName, filename)) {
      this.prepared = null;
      if (Date.now() - p.at < PREPARED_MAX_AGE_MS) {
        const relaySize = findRelayFile(this.room.value, filename)?.size ?? 0;
        const preparedSize =
          p.location.kind === "local" ? p.location.file.size : p.location.kind === "server" ? p.location.playable.sizeBytes : 0;
        if (preparedSize <= 0 || relaySize <= preparedSize) return Promise.resolve(p.location);
      }
    }
    return this.preparing && Filenames.same(this.preparing.fileName, filename) ? this.preparing.lookup : null;
  }

  private async load(media: NowPlaying, resetPosition: boolean, fromRoom: boolean) {
    const token = ++this.loadToken;
    this.nowPlaying.set(media);
    this.status.set({ kind: "loading", fileName: media.fileName });
    this.client?.fileLoading();
    const savedPos = getSeekPosition(media.fileName);
    try {
      const prefs = this.settings.playback;
      await this.player.load(await media.open(), {
        start: savedPos > 0 ? savedPos : undefined,
        audioLanguages: Settings.languages(prefs.audioLanguages),
        subtitleLanguages: Settings.languages(prefs.subtitleLanguages),
      });
    } catch (e) {
      if (token !== this.loadToken) return;
      const reason = (e as Error).message || "Playback failed";
      this.status.set({ kind: "failed", fileName: media.fileName, reason });
      this.postLocal(`Could not play ${media.fileName}: ${reason}`, true);
      return;
    }
    if (token !== this.loadToken) return;
    void this.attachExtras(media, token);
    this.status.set({ kind: "idle" });
    this.schedulePrepare();
    const file: FileInfo = { name: media.fileName, duration: this.player.duration || media.durationHint, size: media.size };
    this.reportedFile = file;
    this.client?.fileLoaded(file, resetPosition, savedPos);
    if (savedPos > 0 && (!this.isActive || resetPosition || this.player.position === 0)) {
      this.player.seek(savedPos);
    }
    if (fromRoom && this.settings.autoReady && this.isActive) this.client?.setReady(true, false);
    const isAudio = FileNames.isAudio(media.fileName);
    const isEpisodeSwitch =
      this.previousMedia !== null &&
      (this.previousMedia.episode !== null || media.episode !== null || this.previousMedia.fileName !== media.fileName);
    this.previousMedia = media;
    clearTimeout(this.autoPlayTimer);
    if (isAudio) {
      if (this.player.isPaused) this.player.setPaused(false);
    } else if (isEpisodeSwitch) {
      this.autoPlayTimer = setTimeout(() => {
        if (this.player.isPaused) this.player.setPaused(false);
      }, 2000);
    } else if (!this.isActive) {
      this.player.setPaused(false);
    }
  }

  /** Plays a file from this device; [inRoom] makes its name the room's selection, so the others load their copy. */
  async playLocal(file: File, inRoom: boolean) {
    const entry = this.library.remember(file);
    const media = localNowPlaying(entry);
    this.known.set(media.fileName, media);
    if (inRoom && this.client && this.isActive) this.client.playInRoom(media.fileName);
    else await this.load(media, inRoom, false);
  }

  /** One season's episodes, in the order they are listed, as a single playlist edit. */
  private addingItems = false;

  async addItems(source: MediaSource, items: MediaItem[]) {
    if (!this.client) {
      this.postLocal("Join a Syncplay room to use the shared playlist", true);
      return;
    }
    const episodes = items.filter(isPlayable);
    if (!episodes.length) {
      this.postLocal("Nothing to add", true);
      return;
    }
    if (this.addingItems) return;
    this.addingItems = true;
    try {
      const names: string[] = [];
      let failed = 0;
      for (const item of episodes) {
        try {
          const media = playableNowPlaying(await source.playable(item));
          this.known.set(media.fileName, media);
          names.push(media.fileName);
        } catch {
          const name = fileNameOf(item);
          if (name) names.push(name);
          else failed++;
        }
      }
      if (!names.length) {
        this.postLocal("Couldn't add those episodes", true);
        return;
      }
      this.reportAdded(names.length, this.add(names));
      if (failed > 0) this.postLocal(`Couldn't add ${failed}`, true);
    } finally {
      this.addingItems = false;
    }
  }

  /**
   * A media server's item: [mode] "room" adds its file name to the shared playlist (if needed) and selects it, so
   * everyone loads their own copy; "here" plays it only here; "add" only adds it. Without a room, it plays here.
   */
  async playItem(source: MediaSource, item: MediaItem, mode: "room" | "here" | "add") {
    let playable: PlayableMedia;
    try {
      playable = await source.playable(item);
    } catch (e) {
      this.postLocal(`Can't play ${item.name}: ${(e as Error).message}`, true);
      return;
    }
    const media = playableNowPlaying(playable);
    this.known.set(media.fileName, media);
    // The same file on the other servers, so playback is reported to them too.
    void this.locator.copiesOf(playable).then((copies) => {
      const known = this.known.get(media.fileName);
      if (known?.key === media.key) this.known.set(media.fileName, { ...known, copies });
    });
    if (mode === "add") {
      if (!this.client) this.postLocal("Join a Syncplay room to use the shared playlist", true);
      else this.reportAdded(1, this.add([media.fileName]));
    } else if (mode === "room" && this.client && this.isActive) {
      this.client.playInRoom(media.fileName);
    } else {
      await this.load(media, mode === "room", false);
    }
  }

  /** Manual pick after a failed lookup: plays a media server's [item] as the playlist entry. */
  async resolveManuallyItem(source: MediaSource, item: MediaItem) {
    const s = this.status.value;
    let playable: PlayableMedia;
    try {
      playable = await source.playable(item);
    } catch (e) {
      this.postLocal(`Can't play ${item.name}: ${(e as Error).message}`, true);
      return;
    }
    const media = playableNowPlaying(playable);
    if (s.kind === "notFound") this.known.set(s.fileName, media);
    await this.load(media, true, true);
  }

  /** Plays a direct link locally; Syncplay identifies streams by their URL. */
  async playUrl(url: string) {
    await this.load(urlNowPlaying(url), false, false);
  }

  /** Opens the untrusted link the room picked, after the user agreed (and trusts its domain with [always]). */
  async playUntrusted(always: boolean) {
    const s = this.status.value;
    if (s.kind !== "untrusted") return;
    if (always && s.domain) {
      this.changeSettings({ ...this.settings, trustedDomains: [...this.settings.trustedDomains, s.domain] });
    }
    await this.load(urlNowPlaying(s.url), true, true);
  }

  /** Manual pick after a failed lookup: plays [file] as the playlist entry. */
  async resolveManually(file: File) {
    const s = this.status.value;
    const entry = this.library.remember(file);
    const media = localNowPlaying(entry);
    if (s.kind === "notFound") this.known.set(s.fileName, { ...media, fileName: s.fileName });
    await this.load(s.kind === "notFound" ? { ...media, fileName: s.fileName } : media, true, true);
  }

  dismissStatus() {
    this.status.set({ kind: "idle" });
  }

  // --- The shared playlist (SharedPlaylist.kt) ------------------------------------------------

  private get playlist(): string[] {
    return this.sent ?? this.room.value.playlist;
  }

  /** Adds the entries that aren't in the playlist yet, before [at] or at the end; returns how many were added. */
  add(files: string[], at: number | null = null): number {
    const added = PlaylistEdits.newEntries(this.playlist, files);
    if (added.length) this.apply(PlaylistEdits.insert(this.playlist, added, at));
    return added.length;
  }

  /** Adds files from this device to the shared playlist in one edit, skipping ones already in it. */
  addLocalFiles(files: File[], at: number | null = null) {
    if (!this.client) {
      this.postLocal("Join a Syncplay room to use the shared playlist", true);
      return;
    }
    const names = files.map((f) => {
      const media = localNowPlaying(this.library.remember(f));
      this.known.set(media.fileName, media);
      return media.fileName;
    });
    this.reportAdded(names.length, this.add(names, at));
  }

  addUrls(urls: string[]): number {
    const valid = urls.map((u) => u.trim()).filter(Filenames.isUrl);
    if (valid.length) this.reportAdded(valid.length, this.add(valid));
    return valid.length;
  }

  addEntries(entries: string[]): number {
    const valid = entries.map((e) => e.trim()).filter(Boolean);
    const added = this.add(valid);
    this.reportAdded(valid.length, added);
    return added;
  }

  private reportAdded(requested: number, added: number) {
    const skipped = requested - added;
    if (added === 0 && skipped > 0) this.postLocal(skipped === 1 ? "That's already in the playlist" : "Those are already in the playlist");
    else if (skipped > 0) this.postLocal(`Added ${added} to the playlist (${skipped} already in it)`);
    else if (added > 0) this.postLocal(added === 1 ? "Added 1 entry to the playlist" : `Added ${added} entries to the playlist`);
  }

  remove(indices: Set<number>) {
    this.apply(PlaylistEdits.remove(this.playlist, indices));
  }

  shift(selected: Set<number>, delta: -1 | 1): Set<number> {
    const [moved, selection] = PlaylistEdits.shift(this.playlist, selected, delta);
    this.apply(moved);
    return selection;
  }

  move(from: number, to: number) {
    this.apply(PlaylistEdits.move(this.playlist, from, to));
  }

  /** Like Syncplay, shuffling the entire playlist starts it over from the first entry. */
  shuffle(entire: boolean) {
    const index = this.room.value.playlistIndex;
    this.apply(PlaylistEdits.shuffle(this.playlist, index, entire));
    if (entire || index === null) this.client?.selectPlaylistIndex(0);
  }

  /** Sorts the playlist A to Z (numbers in number order); the room stays on the entry it's on. */
  sortAlphabetically() {
    this.apply(PlaylistEdits.sortAlphabetically(this.playlist));
  }

  replacePlaylist(files: string[]) {
    this.apply([...new Set(files.map((f) => f.trim()).filter(Boolean))]);
  }

  undo() {
    const client = this.client;
    const previous = this.history.pop();
    if (!client || !previous) return;
    this.canUndo.set(this.history.length > 0);
    this.sent = previous;
    client.setPlaylist(previous);
  }

  selectIndex(index: number) {
    this.client?.selectPlaylistIndex(index);
  }

  /** The entry the next ([forward]) or previous button selects, or null when there's none or the operators decide. */
  skipTarget(forward: boolean): number | null {
    const room = this.room.value;
    if (room.status !== "CONNECTED" || followsOperators(room)) return null;
    return skipTarget(room.playlist.length, room.playlistIndex, forward, this.settings.sync.loopPlaylist);
  }

  skip(forward: boolean) {
    const target = this.skipTarget(forward);
    if (target !== null) this.selectIndex(target);
  }

  private apply(files: string[]) {
    const current = this.playlist;
    const client = this.client;
    if (!client || PlaylistEdits.format(files) === PlaylistEdits.format(current)) return;
    this.record(current);
    this.sent = files;
    client.setPlaylist(files);
  }

  private record(previous: string[]) {
    this.history.push(previous);
    while (this.history.length > MAX_UNDO) this.history.shift();
    this.canUndo.set(true);
  }

  private clearHistory() {
    this.history = [];
    this.sent = null;
    this.canUndo.set(false);
  }
}

/** Next/previous among [size] entries from [index]; with [loop] the ends wrap around. Nothing selected yet: the first. */
export function skipTarget(size: number, index: number | null, forward: boolean, loop: boolean): number | null {
  if (size === 0) return null;
  if (index === null) return forward ? 0 : null;
  if (forward && index + 1 < size) return index + 1;
  if (!forward && index > 0) return index - 1;
  if (loop && size > 1) return forward ? 0 : size - 1;
  return null;
}

export { nextPlaylistIndex };

function localNowPlaying(file: LocalFile): NowPlaying {
  return {
    title: file.name,
    fileName: file.name,
    size: file.size,
    key: `local:${file.folder}/${file.directory}/${file.name}|${file.size}`,
    durationHint: 0,
    copies: [],
    episode: episodeFromFile(file.name, file.directory || null),
    item: null,
    local: file,
    remote: false,
    open: async () => new FileSource(await file.open()),
  };
}

function urlNowPlaying(url: string): NowPlaying {
  let name = url.split("?")[0].split("/").pop() ?? "";
  try {
    name = decodeURIComponent(name);
  } catch {
    // As it is.
  }
  return {
    title: name || url,
    fileName: url,
    size: 0,
    key: url,
    durationHint: 0,
    copies: [],
    episode: episodeFromFile(name),
    item: null,
    local: null,
    remote: true,
    open: () => HttpSource.open(url),
  };
}
