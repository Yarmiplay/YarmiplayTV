import { UnpauseMode, defaultSyncSettings, type SyncSettings } from "../syncplay/models";
import { TrustedDomains } from "../sync/playlistEdits";
import { themeOf, type ThemeKey } from "../ui/theme";
import * as SavedRooms from "./savedRooms";
import type { SavedRoom } from "./savedRooms";

/**
 * The web version's settings, under the apps' own keys (SettingsStore.kt) in this browser's storage, prefixed `ytv:`.
 * Storage can be refused (private windows) or cleared; every read falls back to the default.
 */

const PREFIX = "ytv:";

function read(key: string): string | null {
  try {
    return localStorage.getItem(PREFIX + key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(PREFIX + key);
    else localStorage.setItem(PREFIX + key, value);
  } catch {
    // Not kept past this page; everything still works until it closes.
  }
}

const bool = (key: string, fallback: boolean) => {
  const v = read(key);
  return v === null ? fallback : v === "true";
};
const int = (key: string, fallback: number) => {
  const v = read(key);
  const n = v === null ? NaN : Number(v);
  return Number.isInteger(n) ? n : fallback;
};
const text = (key: string, fallback: string) => read(key) ?? fallback;
const float = (key: string, fallback: number) => {
  const v = read(key);
  const n = v === null ? NaN : Number(v);
  return Number.isFinite(n) ? n : fallback;
};

export interface ConnectionProfile {
  host: string;
  port: number;
  username: string;
  room: string;
  password: string;
}

export interface PlaybackPrefs {
  audioLanguages: string;
  subtitleLanguages: string;
  seekStepSeconds: number;
  stayConnectedMinimized: boolean;
  autoSkipAlone: boolean;
  showSkipButton: boolean;
  detectSkips: boolean;
  detectOnAnyNetwork: boolean;
  /** The browser version's own: names beside the player's icons. */
  playerLabels: boolean;
}

export interface Settings {
  connection: ConnectionProfile;
  sync: SyncSettings;
  autoReady: boolean;
  trustedDomains: string[];
  onlyTrustedDomains: boolean;
  autosavePlaylists: boolean;
  showChat: boolean;
  /** Chat messages pop up over the video as they arrive; off keeps them to the chat panel. */
  chatOnVideo: boolean;
  /** Client "Enable voice" master switch (same key as apps' voice_chat). */
  voiceChat: boolean;
  /** Join voice automatically when entering a room that has it (default on). */
  voiceAutoJoin: boolean;
  /** Capture noise suppression via getUserMedia (default on). */
  voiceNoiseSuppression: boolean;
  /** Mute mic when playback starts/resumes (default on). */
  voiceAutoMuteWhenPlaying: boolean;
  /** Unmute on pause only if auto-mute muted you (default on). */
  voiceAutoUnmuteWhenPaused: boolean;
  /** `enumerateDevices` mic id; empty = system default. */
  voiceInputDeviceId: string;
  /** `setSinkId` speaker id when supported; empty = system default. */
  voiceOutputDeviceId: string;
  voiceInputGain: number;
  voiceOutputGain: number;
  voiceJoinLeaveSounds: boolean;
  voiceJoinLeaveVolume: number;
  theme: ThemeKey;
  acceptedRoomRules: boolean;
  playback: PlaybackPrefs;
  deviceName: string;
  /** A random id for this browser, as the apps keep `device_id` (Jellyfin and Plex tell devices apart by it). */
  deviceId: string;
  shareFiles: boolean;
  /** Sign in by themselves to the Jellyfin a YarmiplayServerTV host shares. */
  addSharedServers: boolean;
  /** The media server that streams a room's file when several have it exactly ("" for the first). */
  preferredServer: string;
  /** Tell the media servers how far playback got, and mark episodes watched. */
  reportPlayback: boolean;
  lastSeenVersion: string;
  savedRooms: SavedRoom[];
}

const UNPAUSE_MODES = Object.values(UnpauseMode) as string[];

/** A name for this browser the host sees, until the user picks one. */
function defaultDeviceName(): string {
  const ua = typeof navigator === "undefined" ? "" : navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const os = /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Windows/.test(ua) ? "Windows" : /Mac OS/.test(ua) ? "Mac" : /Linux/.test(ua) ? "Linux" : "";
  return os ? `${browser} on ${os}` : browser;
}

/** This browser's `device_id`, made once. */
function deviceId(): string {
  let id = read("device_id");
  if (!id) {
    id = typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : String(Math.random()).slice(2);
    write("device_id", id);
  }
  return id;
}

export function load(): Settings {
  const sync = defaultSyncSettings();
  const unpause = read("sync_unpause_mode");
  return {
    connection: {
      host: text("sp_host", ""),
      port: int("sp_port", 8999),
      username: text("sp_username", ""),
      room: text("sp_room", ""),
      password: text("sp_password", ""),
    },
    sync: {
      ...sync,
      rewindOnDesync: bool("sync_rewind", true),
      slowOnDesync: bool("sync_slowdown", true),
      unpauseMode: unpause && UNPAUSE_MODES.includes(unpause) ? (unpause as UnpauseMode) : UnpauseMode.IF_OTHERS_READY,
      readyAtStart: bool("sync_ready_at_start", false),
      sharedPlaylists: bool("sync_shared_playlists", true),
      loopPlaylist: bool("sync_loop_playlist", false),
      loopSingleFile: bool("sync_loop_single_file", false),
    },
    autoReady: bool("sync_auto_ready", true),
    trustedDomains: (read("trusted_domains") ?? TrustedDomains.DEFAULT.join("\n")).split("\n").map((d) => d.trim()).filter(Boolean),
    onlyTrustedDomains: bool("only_trusted_domains", true),
    autosavePlaylists: bool("autosave_playlists", true),
    showChat: bool("chat_show", true),
    chatOnVideo: bool("chat_on_video", false),
    voiceChat: bool("voice_chat", true),
    voiceAutoJoin: bool("voice_auto_join", true),
    voiceNoiseSuppression: bool("voice_noise_suppression", true),
    voiceAutoMuteWhenPlaying: bool("voice_auto_mute_when_playing", true),
    voiceAutoUnmuteWhenPaused: bool("voice_auto_unmute_when_paused", true),
    voiceInputDeviceId: text("voice_input_device", ""),
    voiceOutputDeviceId: text("voice_output_device", ""),
    voiceInputGain: float("voice_input_gain", 1),
    voiceOutputGain: float("voice_output_gain", 1),
    voiceJoinLeaveSounds: bool("voice_join_leave_sounds", true),
    voiceJoinLeaveVolume: float("voice_join_leave_volume", 0.5),
    theme: themeOf(read("app_theme")),
    acceptedRoomRules: bool("room_rules_accepted", false),
    playback: {
      audioLanguages: text("pb_alang", "jpn,ja"),
      subtitleLanguages: text("pb_slang", "eng,en"),
      seekStepSeconds: int("pb_seek_step", 10),
      stayConnectedMinimized: bool("stay_connected_minimized", true),
      autoSkipAlone: bool("pb_auto_skip", false),
      showSkipButton: bool("pb_skip_button", true),
      detectSkips: bool("pb_detect_skips", true),
      detectOnAnyNetwork: bool("pb_detect_any_network", false),
      playerLabels: bool("pb_player_labels", false),
    },
    deviceName: text("device_name", "") || defaultDeviceName(),
    deviceId: deviceId(),
    shareFiles: bool("share_files", true),
    addSharedServers: bool("add_shared_servers", true),
    preferredServer: text("preferred_server", ""),
    reportPlayback: bool("report_playback", true),
    lastSeenVersion: text("last_seen_version", ""),
    savedRooms: SavedRooms.normalize(SavedRooms.decode(text("sp_saved_rooms", ""))),
  };
}

/** Writes [settings] back under the apps' keys. */
export function save(settings: Settings) {
  const c = settings.connection;
  write("sp_host", c.host.trim());
  write("sp_port", String(c.port));
  write("sp_username", c.username.trim());
  write("sp_room", c.room.trim());
  write("sp_password", c.password);
  const s = settings.sync;
  write("sync_rewind", String(s.rewindOnDesync));
  write("sync_slowdown", String(s.slowOnDesync));
  write("sync_unpause_mode", s.unpauseMode);
  write("sync_ready_at_start", String(s.readyAtStart));
  write("sync_shared_playlists", String(s.sharedPlaylists));
  write("sync_loop_playlist", String(s.loopPlaylist));
  write("sync_loop_single_file", String(s.loopSingleFile));
  write("sync_auto_ready", String(settings.autoReady));
  write("trusted_domains", settings.trustedDomains.join("\n"));
  write("only_trusted_domains", String(settings.onlyTrustedDomains));
  write("autosave_playlists", String(settings.autosavePlaylists));
  write("chat_show", String(settings.showChat));
  write("chat_on_video", String(settings.chatOnVideo));
  write("voice_chat", String(settings.voiceChat));
  write("voice_auto_join", String(settings.voiceAutoJoin));
  write("voice_noise_suppression", String(settings.voiceNoiseSuppression));
  write("voice_auto_mute_when_playing", String(settings.voiceAutoMuteWhenPlaying));
  write("voice_auto_unmute_when_paused", String(settings.voiceAutoUnmuteWhenPaused));
  write("voice_input_device", settings.voiceInputDeviceId);
  write("voice_output_device", settings.voiceOutputDeviceId);
  write("voice_input_gain", String(settings.voiceInputGain));
  write("voice_output_gain", String(settings.voiceOutputGain));
  write("voice_join_leave_sounds", String(settings.voiceJoinLeaveSounds));
  write("voice_join_leave_volume", String(settings.voiceJoinLeaveVolume));
  write("app_theme", settings.theme);
  write("room_rules_accepted", String(settings.acceptedRoomRules));
  const p = settings.playback;
  write("pb_alang", p.audioLanguages);
  write("pb_slang", p.subtitleLanguages);
  write("pb_seek_step", String(p.seekStepSeconds));
  write("stay_connected_minimized", String(p.stayConnectedMinimized));
  write("pb_auto_skip", String(p.autoSkipAlone));
  write("pb_skip_button", String(p.showSkipButton));
  write("pb_detect_skips", String(p.detectSkips));
  write("pb_detect_any_network", String(p.detectOnAnyNetwork));
  write("pb_player_labels", String(p.playerLabels));
  write("device_name", settings.deviceName);
  write("share_files", String(settings.shareFiles));
  write("add_shared_servers", String(settings.addSharedServers));
  write("preferred_server", settings.preferredServer);
  write("report_playback", String(settings.reportPlayback));
  write("last_seen_version", settings.lastSeenVersion);
  write("sp_saved_rooms", SavedRooms.encode(settings.savedRooms));
}

/** Each room's playlist, kept per "host:port/room" (PlaylistAutosave). */
export const roomPlaylist = (key: string): string[] => (read(`room_playlist:${key}`) ?? "").split("\n").filter(Boolean);
export const saveRoomPlaylist = (key: string, playlist: string[]) =>
  write(`room_playlist:${key}`, playlist.length ? playlist.join("\n") : null);

/** Managed rooms' operator passwords, by "host:port/+name:HASH". */
export const roomPassword = (key: string): string | null => read(`room_password:${key}`);
export const saveRoomPassword = (key: string, password: string | null) => write(`room_password:${key}`, password);

/** How a server is named in settings: "host:port", host lowercased. */
export const serverKey = (host: string, port: number) => `${host.trim().toLowerCase()}:${port}`;

/** Splits "a, b,c" language lists. */
export const languages = (list: string) => list.split(",").map((l) => l.trim()).filter(Boolean);
