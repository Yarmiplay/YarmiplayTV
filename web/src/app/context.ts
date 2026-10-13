import buildInfo from "virtual:build-info";
import * as SettingsData from "../lib/data/settings";
import { LocalLibrary } from "../lib/local/library";
import type { MediaPlayer } from "../lib/player/mediaPlayer";
import { Store } from "../lib/store";
import { RoomSession } from "../lib/sync/room";
import type { DeviceAuth } from "../lib/syncplay/yarmiplay";
import { applyTheme } from "../lib/ui/theme";
import { BrowserDeviceKeys } from "../lib/yarmiplay/deviceKeys";

/** The app's one of everything: settings, this device's files, the player and the room. */

export const settings = new Store(SettingsData.load());

/** Saves and applies a change to the settings. */
export function changeSettings(change: (s: SettingsData.Settings) => SettingsData.Settings) {
  const next = change(settings.value);
  SettingsData.save(next);
  settings.set(next);
  applyTheme(next.theme);
  session.value?.updateSettings(next);
}

export const library = new LocalLibrary();
void library.restore();

/** This browser's device keys, named as Settings > Syncing says at the time they're used. */
const keys = new BrowserDeviceKeys("");
const deviceAuth: DeviceAuth = {
  get deviceName() {
    return settings.value.deviceName;
  },
  publicKey: (id) => keys.publicKey(id),
  sign: (id, message) => keys.sign(id, message),
};

/** The room session, made once the player exists (it needs the player's canvases). */
export const session = new Store<RoomSession | null>(null);
export const player = new Store<MediaPlayer | null>(null);

export function attachPlayer(p: MediaPlayer) {
  player.set(p);
  session.set(new RoomSession(p, library, settings.value, deviceAuth, buildInfo.version, (s) => settings.set(s)));
}

/** Where the app is: the hash route. */
export const route = new Store(location.hash || "#/");
addEventListener("hashchange", () => route.set(location.hash || "#/"));

export function go(to: string) {
  if (location.hash !== to) location.hash = to;
  else route.set(to);
}

export const version = buildInfo.version;

// For the end-to-end tests and for looking into a room from the browser's console.
(globalThis as unknown as { ytv: unknown }).ytv = { settings, session, player, library };
