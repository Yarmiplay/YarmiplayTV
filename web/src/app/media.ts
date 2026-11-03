import buildInfo from "virtual:build-info";
import {
  clientInfo,
  JellyfinClient,
  JellyfinSource,
  jellyfinKeyOf,
  jellyfinMigrateStoredKey,
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
import { changeSettings, settings } from "./settings";

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

// 2.8.7+: rewrite preferred keys still stored as jellyfin:<ServerId> to jellyfin:<url>.
{
  const preferred = settings.value.preferredServer;
  const migrated = jellyfinMigrateStoredKey(preferred, sessions.value.jellyfin);
  if (migrated !== preferred) changeSettings((s) => ({ ...s, preferredServer: migrated }));
}

function build(s: Sessions): MediaSource[] {
  return [
    ...s.jellyfin.map(
      (j) =>
        new JellyfinSource(jellyfinClient, j, Date.now, (previous, moved) => {
          replaceJellyfin(previous, moved);
        }),
    ),
    ...s.plex.map((p) => new PlexSource(plexClient, p)),
  ];
}

/** One source per signed-in server, rebuilt when the sessions change. */
export const sources = new Store<MediaSource[]>(build(sessions.value));
sessions.subscribe((s) => sources.set(build(s)));

function change(next: Sessions) {
  saveSessions(storage, next);
  sessions.set(next);
}

/** HTTPS saved as duckdns:8999 while Noire/Jellyfin only speaks HTTP there → rewrite that entry to http://. */
void (async () => {
  for (const session of sessions.value.jellyfin) {
    if (!/^https:/i.test(session.serverUrl)) continue;
    try {
      await jellyfinClient.validate(session);
    } catch (e) {
      const moved = await jellyfinClient.sessionOnPlainHttp(session, e);
      if (moved) replaceJellyfin(session, moved);
    }
  }
})();

export function addJellyfin(session: JellyfinSession) {
  const key = jellyfinKeyOf(session);
  const others = sessions.value.jellyfin.filter((j) => jellyfinKeyOf(j) !== key);
  change({ ...sessions.value, jellyfin: [...others, session] });
}

/** Updates a saved Jellyfin entry when its address (and thus key) changed, e.g. a shared host moved. */
export function replaceJellyfin(previous: JellyfinSession, session: JellyfinSession) {
  const oldKey = jellyfinKeyOf(previous);
  const newKey = jellyfinKeyOf(session);
  const others = sessions.value.jellyfin.filter((j) => {
    const k = jellyfinKeyOf(j);
    return k !== oldKey && k !== newKey;
  });
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
