import buildInfo from "virtual:build-info";
import {
  clientInfo,
  JellyfinClient,
  JellyfinSource,
  jellyfinKeyOf,
  jellyfinMigrateStoredKey,
  loadSessions,
  migratePreferHttps,
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

const rawLoaded = loadSessions(storage);
const migratedJellyfin = rawLoaded.jellyfin.map(migratePreferHttps);
const loaded: Sessions = { ...rawLoaded, jellyfin: migratedJellyfin };
export const sessions = new Store<Sessions>(loaded);
if (JSON.stringify(migratedJellyfin) !== JSON.stringify(rawLoaded.jellyfin)) {
  saveSessions(storage, loaded);
}

// 2.8.7+: rewrite preferred keys still stored as jellyfin:<ServerId> to jellyfin:<url>.
// 2.9.2+: also http:// → https:// after preferHttps migration.
{
  const preferred = settings.value.preferredServer;
  const migrated = jellyfinMigrateStoredKey(preferred, sessions.value.jellyfin);
  if (migrated !== preferred) changeSettings((s) => ({ ...s, preferredServer: migrated }));
}

function build(s: Sessions): MediaSource[] {
  return [
    ...s.jellyfin.map((j) => new JellyfinSource(jellyfinClient, j)),
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

/**
 * Prefer HTTPS on each load: validate at the probe URL; on a plain-HTTP SSL failure, adopt HTTP for this page
 * session only (sources keep requestBase) without rewriting the saved entry.
 */
void (async () => {
  for (const source of sources.value) {
    if (!(source instanceof JellyfinSource)) continue;
    source.resetToPreferredProbe();
    try {
      await jellyfinClient.validate(source.session);
    } catch (e) {
      await source.adoptPlainHttpFallback(e);
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
