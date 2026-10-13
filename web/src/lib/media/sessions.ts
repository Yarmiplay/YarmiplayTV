import { jellyfinSession, type JellyfinSession } from "./jellyfin/client";
import type { PlexSession } from "./plex/client";

/**
 * The signed-in servers, kept as SettingsStore.kt keeps them: JSON lists of [JellyfinSession] and [PlexSession] under
 * `jf_servers` and `px_servers`, with the Kotlin classes' field names, so a list from the apps reads here and back.
 */

export const JELLYFIN_SERVERS_KEY = "jf_servers";
export const PLEX_SERVERS_KEY = "px_servers";

/** The part of `localStorage` used here. */
export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export interface Sessions {
  jellyfin: JellyfinSession[];
  plex: PlexSession[];
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const str = (r: Record<string, unknown>, k: string): string => (typeof r[k] === "string" ? (r[k] as string) : "");

function readList(storage: KeyValueStorage, key: string): Record<string, unknown>[] {
  let text: string | null;
  try {
    text = storage.getItem(key);
  } catch {
    return [];
  }
  if (text === null) return [];
  try {
    const parsed: unknown = JSON.parse(text);
    return Array.isArray(parsed) ? parsed.filter(isRecord) : [];
  } catch {
    return [];
  }
}

/** A stored Jellyfin entry, or null when it lacks the token (nothing to sign in with). */
export function decodeJellyfin(r: Record<string, unknown>): JellyfinSession | null {
  if (!str(r, "accessToken") || !str(r, "serverUrl")) return null;
  return jellyfinSession({
    serverUrl: str(r, "serverUrl"),
    serverName: str(r, "serverName"),
    serverId: str(r, "serverId"),
    userId: str(r, "userId"),
    userName: str(r, "userName"),
    accessToken: str(r, "accessToken"),
    sharedBy: str(r, "sharedBy"),
    candidateUrls: Array.isArray(r.candidateUrls) ? r.candidateUrls.filter((u): u is string => typeof u === "string") : [],
    noLongerShared: r.noLongerShared === true,
  });
}

/** A stored Plex entry, or null when it lacks the server token. */
export function decodePlex(r: Record<string, unknown>): PlexSession | null {
  if (!str(r, "serverToken") || !str(r, "serverUrl")) return null;
  return {
    serverUrl: str(r, "serverUrl"),
    serverName: str(r, "serverName"),
    machineId: str(r, "machineId"),
    userName: str(r, "userName"),
    serverToken: str(r, "serverToken"),
    accountToken: str(r, "accountToken") || str(r, "serverToken"),
  };
}

/**
 * The servers kept in [storage]; unreadable or missing lists are empty. [prefix] goes before each key, as the web's
 * settings put `ytv:` before the apps' keys.
 */
export function loadSessions(storage: KeyValueStorage, prefix = "ytv:"): Sessions {
  return {
    jellyfin: readList(storage, prefix + JELLYFIN_SERVERS_KEY).flatMap((r) => decodeJellyfin(r) ?? []),
    plex: readList(storage, prefix + PLEX_SERVERS_KEY).flatMap((r) => decodePlex(r) ?? []),
  };
}

/** Replaces both kept lists, as SettingsStore.saveServers does; storage that refuses (private windows) keeps nothing. */
export function saveSessions(storage: KeyValueStorage, sessions: Sessions, prefix = "ytv:"): void {
  try {
    storage.setItem(prefix + JELLYFIN_SERVERS_KEY, JSON.stringify(sessions.jellyfin));
    storage.setItem(prefix + PLEX_SERVERS_KEY, JSON.stringify(sessions.plex));
  } catch {
    // Not kept past this page; the servers still work until it closes.
  }
}
