import buildInfo from "virtual:build-info";
import {
  clientInfo,
  JellyfinClient,
  JellyfinSource,
  loadSessions,
  PlexClient,
  PlexSource,
  saveSessions,
  type JellyfinSession,
  type MediaSource,
  type PlexSession,
  type Sessions,
} from "../lib/media";
import { Store } from "../lib/store";
import { settings } from "./context";

/**
 * The media servers this browser is signed in to (Settings > Media servers): Jellyfin and Plex sessions kept under the
 * apps' `jf_servers` and `px_servers`, and the sources made from them for browsing, searching and the playlist lookup.
 */

const storage = {
  getItem: (key: string) => {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  setItem: (key: string, value: string) => {
    try {
      localStorage.setItem(key, value);
    } catch {
      // Kept until the page closes.
    }
  },
};

const info = () => clientInfo({ deviceName: settings.value.deviceName, deviceId: settings.value.deviceId, version: buildInfo.version });

export const jellyfinClient = new JellyfinClient(info());
export const plexClient = new PlexClient(info());

export const sessions = new Store<Sessions>(loadSessions(storage));

function build(s: Sessions): MediaSource[] {
  return [...s.jellyfin.map((j) => new JellyfinSource(jellyfinClient, j)), ...s.plex.map((p) => new PlexSource(plexClient, p))];
}

/** One source per signed-in server, rebuilt when the sessions change. */
export const sources = new Store<MediaSource[]>(build(sessions.value));
sessions.subscribe((s) => sources.set(build(s)));

function change(next: Sessions) {
  saveSessions(storage, next);
  sessions.set(next);
}

export function addJellyfin(session: JellyfinSession) {
  const others = sessions.value.jellyfin.filter((j) => !(j.serverId === session.serverId && j.userId === session.userId));
  change({ ...sessions.value, jellyfin: [...others, session] });
}

export function addPlex(session: PlexSession) {
  const others = sessions.value.plex.filter((p) => p.machineId !== session.machineId);
  change({ ...sessions.value, plex: [...others, session] });
}

/** Signs out of the server [source] reads (and tells Jellyfin, which keeps sessions). */
export async function remove(source: MediaSource) {
  if (source instanceof JellyfinSource) {
    await jellyfinClient.logout(source.session).catch(() => {});
    change({ ...sessions.value, jellyfin: sessions.value.jellyfin.filter((j) => j !== source.session) });
  } else if (source instanceof PlexSource) {
    change({ ...sessions.value, plex: sessions.value.plex.filter((p) => p !== source.session) });
  }
}

export const sourceByKey = (key: string): MediaSource | undefined => sources.value.find((s) => s.key === key);
