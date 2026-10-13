import { describe, expect, it } from "vitest";
import { jellyfinSession } from "./jellyfin/client";
import type { PlexSession } from "./plex/client";
import { loadSessions, saveSessions, type KeyValueStorage } from "./sessions";

class MemoryStorage implements KeyValueStorage {
  readonly items = new Map<string, string>();
  getItem(key: string) {
    return this.items.get(key) ?? null;
  }
  setItem(key: string, value: string) {
    this.items.set(key, value);
  }
}

const jf = jellyfinSession({ serverUrl: "https://jf.example.com", serverName: "Benny", serverId: "srv", userId: "u1", userName: "yarmi", accessToken: "tok" });
const px: PlexSession = {
  serverUrl: "https://10-0-0-5.abc.plex.direct:32400",
  serverName: "Den",
  machineId: "mid",
  userName: "yarmi",
  serverToken: "srvtok",
  accountToken: "acct",
};

describe("sessions", () => {
  it("round-trip under the apps' keys", () => {
    const storage = new MemoryStorage();
    saveSessions(storage, { jellyfin: [jf], plex: [px] });
    expect([...storage.items.keys()]).toEqual(["ytv:jf_servers", "ytv:px_servers"]);
    expect(loadSessions(storage)).toEqual({ jellyfin: [jf], plex: [px] });
  });

  it("read the apps' JSON, where defaults are left out", () => {
    const storage = new MemoryStorage();
    // What kotlinx.serialization writes for JellyfinSession and PlexSession (defaults omitted).
    storage.setItem(
      "jf_servers",
      `[{"serverUrl":"https://jf.example.com","serverName":"Benny","serverId":"srv","userId":"u1","userName":"yarmi","accessToken":"tok"},
        {"serverUrl":"https://host.example:8999","serverName":"Shared","serverId":"s2","userId":"g","userName":"guest","accessToken":"t2",
         "sharedBy":"host.example:8999","candidateUrls":["https://a","https://b"],"noLongerShared":true}]`,
    );
    storage.setItem("px_servers", JSON.stringify([px]));
    const sessions = loadSessions(storage, "");
    expect(sessions.jellyfin[0]).toEqual(jf);
    expect(sessions.jellyfin[1]).toMatchObject({ sharedBy: "host.example:8999", candidateUrls: ["https://a", "https://b"], noLongerShared: true });
    expect(sessions.plex).toEqual([px]);
  });

  it("missing, broken or refused storage reads as no servers", () => {
    expect(loadSessions(new MemoryStorage())).toEqual({ jellyfin: [], plex: [] });
    const broken = new MemoryStorage();
    broken.setItem("ytv:jf_servers", "{not json");
    broken.setItem("ytv:px_servers", `[{"serverUrl":"x"}]`);
    expect(loadSessions(broken)).toEqual({ jellyfin: [], plex: [] });
    const refusing: KeyValueStorage = {
      getItem: () => {
        throw new Error("denied");
      },
      setItem: () => {
        throw new Error("denied");
      },
    };
    expect(loadSessions(refusing)).toEqual({ jellyfin: [], plex: [] });
    expect(() => saveSessions(refusing, { jellyfin: [jf], plex: [] })).not.toThrow();
  });
});
