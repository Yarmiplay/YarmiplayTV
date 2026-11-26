import { describe, expect, it } from "vitest";
import { load } from "../data/settings";
import { evaluateWebPreloadGate, upcomingPlaylistIndices } from "./preloader";

describe("web playlist preloader policy", () => {
  it("defaults ON for Wi-Fi/Ethernet and OFF for mobile data", () => {
    const s = load();
    expect(s.preDownloadPlaylistWifi).toBe(true);
    expect(s.preDownloadPlaylistMobile).toBe(false);
    expect(s.playback.onlineSubtitlesSearch).toBe(false);
    expect(s.playback.onlineSubtitlesLanguage).toBe("en");
  });

  it("strictly yields to active playback when loading or buffering", () => {
    const s = load();
    expect(
      evaluateWebPreloadGate({
        settings: s,
        unmetered: true,
        hasNowPlaying: true,
        player: { loaded: true, buffering: false },
        statusKind: "idle",
      }),
    ).toBe("allowed");

    expect(
      evaluateWebPreloadGate({
        settings: s,
        unmetered: false,
        hasNowPlaying: true,
        player: { loaded: true, buffering: false },
        statusKind: "idle",
      }),
    ).toBe("disabled");

    expect(
      evaluateWebPreloadGate({
        settings: s,
        unmetered: true,
        hasNowPlaying: true,
        player: { loaded: true, buffering: true },
        statusKind: "idle",
      }),
    ).toBe("yield");

    expect(
      evaluateWebPreloadGate({
        settings: s,
        unmetered: true,
        hasNowPlaying: true,
        player: { loaded: true, buffering: false },
        statusKind: "resolving",
      }),
    ).toBe("yield");
  });

  it("orders upcoming indices starting with the next entry", () => {
    expect(upcomingPlaylistIndices(4, 1, false)).toEqual([2, 3]);
    expect(upcomingPlaylistIndices(4, 1, true)).toEqual([2, 3, 0]);
  });
});
