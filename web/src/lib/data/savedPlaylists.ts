import { Store } from "../store";

export interface SavedPlaylist {
  id: string;
  name: string;
  entries: string[];
  createdAt: number;
}

const STORAGE_KEY = "ytv:saved_playlists";

function loadFromStorage(): SavedPlaylist[] {
  if (typeof localStorage === "undefined") return [];
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function persistToStorage(playlists: SavedPlaylist[]) {
  if (typeof localStorage === "undefined") return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(playlists));
  } catch {
    // Ignore storage quota errors
  }
}

export const savedPlaylistsStore = new Store<SavedPlaylist[]>(loadFromStorage());

/** Default name: today's date and the first entry's file name. */
export function defaultSavedPlaylistName(entries: string[], today = new Date().toISOString().slice(0, 10)): string {
  const first = entries
    .map((e) => e.trim())
    .filter(Boolean)
    .map((e) => e.split(/[/\\]/).pop() || e)
    .map((e) => e.slice(0, 48))[0];
  return first ? `${today} · ${first}` : today;
}

export function formatSavedPlaylistDate(createdAt: number): string {
  if (!createdAt || createdAt <= 0) return "";
  try {
    return new Date(createdAt).toISOString().slice(0, 10);
  } catch {
    return "";
  }
}

export function savePlaylist(name: string, entries: string[]): SavedPlaylist {
  const cleanEntries = entries.map((e) => e.trim()).filter(Boolean);
  const cleanName = name.trim() || defaultSavedPlaylistName(cleanEntries);
  const playlist: SavedPlaylist = {
    id: `pl_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    name: cleanName,
    entries: cleanEntries,
    createdAt: Date.now(),
  };
  const updated = [playlist, ...savedPlaylistsStore.value.filter((p) => p.name !== cleanName)];
  savedPlaylistsStore.set(updated);
  persistToStorage(updated);
  return playlist;
}

export function renameSavedPlaylist(id: string, name: string): boolean {
  const cleanName = name.trim();
  if (!cleanName) return false;
  let renamed = false;
  const updated = savedPlaylistsStore.value.map((p) => {
    if (p.id !== id) return p;
    renamed = true;
    return { ...p, name: cleanName };
  });
  if (!renamed) return false;
  savedPlaylistsStore.set(updated);
  persistToStorage(updated);
  return true;
}

export function deleteSavedPlaylist(id: string) {
  const updated = savedPlaylistsStore.value.filter((p) => p.id !== id);
  savedPlaylistsStore.set(updated);
  persistToStorage(updated);
}
