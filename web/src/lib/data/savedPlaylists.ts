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

export function savePlaylist(name: string, entries: string[]): SavedPlaylist {
  const cleanEntries = entries.map((e) => e.trim()).filter(Boolean);
  const cleanName = name.trim() || `Playlist ${new Date().toLocaleDateString()}`;
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

export function deleteSavedPlaylist(id: string) {
  const updated = savedPlaylistsStore.value.filter((p) => p.id !== id);
  savedPlaylistsStore.set(updated);
  persistToStorage(updated);
}
