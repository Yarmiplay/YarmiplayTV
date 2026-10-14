import { HostJellyfinSharing, type AuthorizeResult } from "../lib/sync/hostJellyfin";
import type { RoomSession } from "../lib/sync/room";
import type { SyncplayClient, SyncplayEvent } from "../lib/syncplay/client";
import { session } from "./context";
import { addJellyfin, jellyfinClient, sessions } from "./media";
import { settings } from "./settings";

/**
 * The Jellyfin a YarmiplayServerTV host shares, added by itself on joining their room (AppContainer.kt's
 * jellyfinSharing): it then shows with the other media servers, labelled "(via the server)".
 */

/** How long the Syncplay server has to answer a Quick Connect authorization (as the apps). */
const AUTHORIZE_TIMEOUT_MS = 30_000;

function authorizeWith(client: SyncplayClient, code: string): Promise<AuthorizeResult> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => finish(null), AUTHORIZE_TIMEOUT_MS);
    const stop = client.onEvent((e: SyncplayEvent) => {
      if (e.type === "jellyfinAuthorized" && e.code === code) finish({ ok: e.ok, error: e.error });
    });
    function finish(result: AuthorizeResult) {
      clearTimeout(timer);
      stop();
      resolve(result);
    }
    client.authorizeJellyfin(code);
  });
}

let current: RoomSession | null = null;

const sharing = new HostJellyfinSharing({
  jellyfin: jellyfinClient,
  saved: () => sessions.value.jellyfin,
  enabled: () => settings.value.addSharedServers,
  save: addJellyfin,
  authorize: async (code) => {
    const client = current?.syncClient;
    return client ? authorizeWith(client, code) : null;
  },
  notify: (text, isError) => current?.postLocal(text, isError),
  log: (text) => console.info(`[host Jellyfin] ${text}`),
});

let stopRoom: (() => void) | null = null;
session.subscribe((s) => {
  stopRoom?.();
  stopRoom = null;
  sharing.stop();
  current = s;
  if (!s) return;
  stopRoom = s.room.subscribe((room) => {
    const client = s.syncClient;
    const ext = client?.session;
    if (room.status !== "CONNECTED" || !client || !ext) return;
    void sharing.offer(ext, {
      shared: room.yarmiplay.jellyfin,
      on: room.yarmiplay.capabilities.jellyfin,
      baseUrl: ext.baseUrl,
      via: s.serverHost ?? new URL(ext.baseUrl).hostname,
    });
  });
});
