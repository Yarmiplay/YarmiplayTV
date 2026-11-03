import { describe, expect, it, beforeEach } from "vitest";
import {
  defaultSavedPlaylistName,
  deleteSavedPlaylist,
  formatSavedPlaylistDate,
  renameSavedPlaylist,
  savePlaylist,
  savedPlaylistsStore,
} from "./savedPlaylists";

describe("savedPlaylists", () => {
  beforeEach(() => {
    savedPlaylistsStore.set([]);
    if (typeof localStorage !== "undefined") localStorage.clear();
  });

  it("saves a playlist and loads it", () => {
    const saved = savePlaylist("Favorites", ["track1.mp3", "track2.flac"]);
    expect(saved.name).toBe("Favorites");
    expect(saved.entries).toEqual(["track1.mp3", "track2.flac"]);
    expect(savedPlaylistsStore.value).toHaveLength(1);
    expect(savedPlaylistsStore.value[0].name).toBe("Favorites");
  });

  it("defaults the name to the date and first entry", () => {
    expect(defaultSavedPlaylistName(["a/b/Show - 01.mkv", "two.mkv"], "2026-11-03")).toBe("2026-11-03 · Show - 01.mkv");
    expect(defaultSavedPlaylistName([], "2026-11-03")).toBe("2026-11-03");
  });

  it("renames and deletes a saved playlist", () => {
    const p1 = savePlaylist("P1", ["a.mp3"]);
    savePlaylist("P2", ["b.mp3"]);
    expect(savedPlaylistsStore.value).toHaveLength(2);
    expect(renameSavedPlaylist(p1.id, "Renamed")).toBe(true);
    expect(savedPlaylistsStore.value.find((p) => p.id === p1.id)?.name).toBe("Renamed");
    deleteSavedPlaylist(p1.id);
    expect(savedPlaylistsStore.value).toHaveLength(1);
    expect(savedPlaylistsStore.value[0].name).toBe("P2");
  });

  it("formats the created date", () => {
    expect(formatSavedPlaylistDate(0)).toBe("");
    expect(formatSavedPlaylistDate(Date.UTC(2026, 10, 3))).toBe("2026-11-03");
  });
});
