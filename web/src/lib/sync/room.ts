import * as Settings from "../data/settings";
import * as SavedRooms from "../data/savedRooms";
import type { LocalFile, LocalLibrary } from "../local/library";
import { FileSource, HttpSource, type ByteSource } from "../player/bytes";
import type { MediaPlayer } from "../player/mediaPlayer";
import { Store } from "../store";
import {
  SyncplayClient,
  followsOperators,
  hasManagedRooms,
  nextPlaylistIndex,
  type RoomState,
  type SyncplayConfig,
  type SyncplayEvent,
} from "../syncplay/client";
import * as Filenames from "../syncplay/filenames";
import * as ManagedRooms from "../syncplay/managedRooms";
import type { FileInfo } from "../syncplay/models";
import type { DeviceAuth } from "../syncplay/yarmiplay";
import * as PlaylistEdits from "./playlistEdits";
import { TrustedDomains } from "./playlistEdits";

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

/** What the player is (about to be) showing. */
export interface NowPlaying {
  title: string;
  /** The name the room knows it by: the file's name, or the URL. */
  fileName: string;
  size: number;
  open(): Promise<ByteSource>;
}

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

  constructor(
    private readonly player: MediaPlayer,
    private readonly library: LocalLibrary,
    private settings: Settings.Settings,
    private readonly deviceAuth: DeviceAuth | null,
    private readonly appVersion: string,
    private readonly onSettingsChanged: (s: Settings.Settings) => void = () => {},
  ) {}

  get isActive(): boolean {
    return this.client !== null && this.room.value.status !== "DISCONNECTED";
  }

  get syncClient(): SyncplayClient | null {
    return this.client;
  }

  updateSettings(settings: Settings.Settings) {
    this.settings = settings;
    if (this.client) this.client.settings = settings.sync;
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

    const local = /^(localhost|127\.|\[::1\])/.test(profile.host.trim()) && location.protocol === "http:";
    const config: SyncplayConfig = {
      host: profile.host.trim(),
      port: profile.port,
      username: profile.username.trim(),
      room,
      password: profile.password || null,
      // wss:// always, but for a server on this computer while developing from http://localhost.
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
    if (this.reportedFile) client.fileLoaded(this.reportedFile, false);
  }

  /** Joins again with the last join's settings, e.g. to ask a YarmiplayServerTV host for access. */
  rejoin(requestAccess = true) {
    if (this.lastJoin) this.join(this.lastJoin.profile, { ...this.lastJoin.options, requestAccess, createManagedRoom: undefined });
  }

  disconnect() {
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
    const known = [...this.known.entries()].find(([k]) => Filenames.same(k, filename))?.[1];
    if (known) {
      await this.load(known, resetPosition, true);
      return;
    }
    // Step 1 of MediaLocator.locateForRoom: this device's files. (Media servers and the relay come next.)
    this.status.set({ kind: "resolving", fileName: filename });
    const match = this.library.find(filename);
    if (match) {
      await this.load(localNowPlaying(match.file), resetPosition, true);
      return;
    }
    const reason = this.library.hasFolders ? "Not in your media folders" : "Add a media folder, or pick the file, to play the room's entries";
    this.status.set({ kind: "notFound", fileName: filename, reason });
    this.postLocal(`'${filename}' not found: ${reason.toLowerCase()}`, true);
  }

  private async load(media: NowPlaying, resetPosition: boolean, fromRoom: boolean) {
    const token = ++this.loadToken;
    this.nowPlaying.set(media);
    this.status.set({ kind: "loading", fileName: media.fileName });
    this.client?.fileLoading();
    try {
      const prefs = this.settings.playback;
      await this.player.load(await media.open(), {
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
    this.status.set({ kind: "idle" });
    const file: FileInfo = { name: media.fileName, duration: this.player.duration, size: media.size };
    this.reportedFile = file;
    this.client?.fileLoaded(file, resetPosition);
    if (fromRoom && this.settings.autoReady && this.isActive) this.client?.setReady(true, false);
    if (!this.isActive) this.player.setPaused(false);
  }

  /** Plays a file from this device; [inRoom] makes its name the room's selection, so the others load their copy. */
  async playLocal(file: File, inRoom: boolean) {
    const entry = this.library.remember(file);
    const media = localNowPlaying(entry);
    this.known.set(media.fileName, media);
    if (inRoom && this.client && this.isActive) this.client.playInRoom(media.fileName);
    else await this.load(media, inRoom, false);
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
  return { title: name || url, fileName: url, size: 0, open: () => HttpSource.open(url) };
}
