import { describe, expect, it, beforeEach } from "vitest";
import { savePlaylist, deleteSavedPlaylist, savedPlaylistsStore } from "./savedPlaylists";

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

  it("deletes a saved playlist", () => {
    const p1 = savePlaylist("P1", ["a.mp3"]);
    savePlaylist("P2", ["b.mp3"]);
    expect(savedPlaylistsStore.value).toHaveLength(2);
    deleteSavedPlaylist(p1.id);
    expect(savedPlaylistsStore.value).toHaveLength(1);
    expect(savedPlaylistsStore.value[0].name).toBe("P2");
  });
});
