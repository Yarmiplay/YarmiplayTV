import * as SettingsData from "../lib/data/settings";
import { Store } from "../lib/store";
import { applyTheme } from "../lib/ui/theme";

/** The settings, loaded once; changes go through [changeSettings], which saves them and tells whoever listens. */
export const settings = new Store(SettingsData.load());

const listeners = new Set<(s: SettingsData.Settings) => void>();

/** Saves and applies a change to the settings. */
export function changeSettings(change: (s: SettingsData.Settings) => SettingsData.Settings) {
  const next = change(settings.value);
  SettingsData.save(next);
  settings.set(next);
  applyTheme(next.theme);
  for (const l of listeners) l(next);
}

/** Called after every change made with [changeSettings]. */
export function onSettingsChanged(listener: (s: SettingsData.Settings) => void) {
  listeners.add(listener);
}
