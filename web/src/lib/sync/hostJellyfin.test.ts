import { describe, expect, it } from "vitest";
import { jellyfinSession, MediaSourceError, type JellyfinSession, type QuickConnectOptions } from "../media";
import type { SharedJellyfin } from "../syncplay/yarmiplay";
import { candidates, HostJellyfinSharing, type AuthorizeResult, type HostJellyfinDeps, type JellyfinOffer } from "./hostJellyfin";

const SYNC = "https://sync.example:8999";
const shared = (over: Partial<SharedJellyfin> = {}): SharedJellyfin => ({
  available: true,
  serverId: "jf-1",
  serverName: "Jelly",
  proxy: true,
  addresses: ["http://192.168.1.20:8096"],
  ...over,
});
const offer = (over: Partial<SharedJellyfin> = {}, on = true): JellyfinOffer => ({ shared: shared(over), on, baseUrl: SYNC, via: "sync.example" });

/** A Jellyfin that answers as [ids] at each address, approving Quick Connect once the server authorizes the code. */
function harness(opts: { ids?: Record<string, string>; quickConnect?: boolean; authorize?: AuthorizeResult; valid?: Record<string, boolean | "unreachable">; saved?: JellyfinSession[]; enabled?: boolean } = {}) {
  const ids = opts.ids ?? { [SYNC]: "jf-1" };
  const saved: JellyfinSession[] = [...(opts.saved ?? [])];
  const notes: { text: string; isError: boolean }[] = [];
  const codes: string[] = [];
  const probed: string[] = [];
  let signIns = 0;
  const deps: HostJellyfinDeps = {
    jellyfin: {
      normalizeServerUrl: (url) => url.replace(/\/+$/, ""),
      publicInfo: async (base) => {
        probed.push(base);
        if (!(base in ids)) throw new MediaSourceError("unreachable");
        return { Id: ids[base], ServerName: "Jellyfin on the host" };
      },
      quickConnectEnabled: async () => opts.quickConnect ?? true,
      quickConnect: (base: string, qc: QuickConnectOptions) =>
        new Promise<JellyfinSession>((resolve, reject) => {
          signIns++;
          qc.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
          qc.onCode("ABC123");
          // Jellyfin authenticates once the code is approved; a refused code never is.
          setTimeout(() => {
            if ((opts.authorize ?? { ok: true, error: null })?.ok)
              resolve(jellyfinSession({ serverUrl: base, serverName: "Jellyfin on the host", serverId: ids[base], userId: "guest", userName: "guest", accessToken: "tok" }));
          }, 5);
        }),
      validate: async (s) => {
        const v = opts.valid?.[s.serverUrl];
        if (v === "unreachable" || v === undefined) throw new MediaSourceError("unreachable");
        return v;
      },
    },
    saved: () => saved,
    enabled: () => opts.enabled ?? true,
    save: (s) => {
      const i = saved.findIndex((x) => x.serverId === s.serverId);
      if (i >= 0) saved[i] = s;
      else saved.push(s);
    },
    authorize: async (code) => {
      codes.push(code);
      return opts.authorize === undefined ? { ok: true, error: null } : opts.authorize;
    },
    notify: (text, isError = false) => notes.push({ text, isError }),
    pageProtocol: () => "https:",
  };
  return { sharing: new HostJellyfinSharing(deps), saved, notes, codes, probed, signIns: () => signIns };
}

describe("candidates", () => {
  it("tries the Syncplay server's proxy before the host's addresses (as the apps)", () => {
    const s = shared({ addresses: ["https://jf.example", "https://sync.example:8999"] });
    expect(candidates(s, SYNC, "http:")).toEqual([SYNC, "https://jf.example"]);
    expect(candidates({ ...s, proxy: false }, SYNC, "http:")).toEqual(["https://jf.example", SYNC]);
  });

  it("leaves out the plain-http addresses an https page may not load, but not this computer's", () => {
    const s = shared({ addresses: ["http://192.168.1.20:8096", "10.0.0.5:8096", "https://jf.example", "http://localhost:8096"] });
    expect(candidates(s, SYNC, "https:")).toEqual([SYNC, "https://jf.example", "http://localhost:8096"]);
    expect(candidates(s, SYNC, "http:")).toEqual([SYNC, "http://192.168.1.20:8096", "10.0.0.5:8096", "https://jf.example", "http://localhost:8096"]);
  });
});

describe("HostJellyfinSharing", () => {
  it("signs in through the Syncplay server with the code it approves, and saves it as the host's", async () => {
    const h = harness();
    await h.sharing.offer("session-1", offer());
    expect(h.codes).toEqual(["ABC123"]);
    expect(h.saved).toHaveLength(1);
    expect(h.saved[0]).toMatchObject({ serverUrl: SYNC, serverName: "Jelly (via sync.example)", sharedBy: "sync.example", candidateUrls: [SYNC], noLongerShared: false });
    expect(h.notes).toEqual([{ text: "Added Jelly, shared by the Syncplay host", isError: false }]);
    // The plain-http LAN address isn't tried from an https page.
    expect(h.probed).toEqual([SYNC]);
  });

  it("asks once per session, and again on a new one", async () => {
    const h = harness();
    await h.sharing.offer("session-1", offer());
    await h.sharing.offer("session-1", offer());
    expect(h.signIns()).toBe(1);
    // Saved and valid now: a new session checks it rather than signing in again.
    h.saved[0] = { ...h.saved[0] };
    await h.sharing.offer("session-2", offer());
    expect(h.signIns()).toBe(1);
  });

  it("does nothing when switched off, not shared or the capability is off", async () => {
    for (const [h, o] of [
      [harness({ enabled: false }), offer()],
      [harness(), offer({ available: false })],
      [harness(), offer({}, false)],
      [harness(), offer({ serverId: "" })],
    ] as const) {
      await h.sharing.offer("s", o);
      expect(h.saved).toEqual([]);
      expect(h.codes).toEqual([]);
    }
  });

  it("says so when the server turns the code down, and doesn't ask again on that session", async () => {
    const h = harness({ authorize: { ok: false, error: "No guest account" } });
    await h.sharing.offer("s", offer());
    expect(h.saved).toEqual([]);
    expect(h.notes).toEqual([{ text: "Couldn't add the host's Jellyfin: No guest account", isError: true }]);
    await h.sharing.offer("s", offer());
    expect(h.signIns()).toBe(1);
  });

  it("doesn't sign in when no address answers as that server, or Quick Connect is off", async () => {
    const wrong = harness({ ids: { [SYNC]: "someone-else" } });
    await wrong.sharing.offer("s", offer());
    expect(wrong.signIns()).toBe(0);
    const off = harness({ quickConnect: false });
    await off.sharing.offer("s", offer());
    expect(off.signIns()).toBe(0);
    expect(off.saved).toEqual([]);
  });

  it("leaves a server the user signed in to themselves alone, even with an expired sign-in", async () => {
    const own = jellyfinSession({ serverUrl: "https://jf.example", serverName: "Mine", serverId: "jf-1", userId: "me", userName: "me", accessToken: "x" });
    const h = harness({ saved: [own], valid: { "https://jf.example": false } });
    await h.sharing.offer("s", offer());
    expect(h.signIns()).toBe(0);
    expect(h.saved).toEqual([own]);
  });

  it("moves a shared server whose address stopped answering to one that does", async () => {
    const old = jellyfinSession({ serverUrl: "https://old.example", serverName: "Jelly (via sync.example)", serverId: "jf-1", userId: "guest", userName: "guest", accessToken: "tok", sharedBy: "sync.example" });
    const h = harness({ saved: [old], valid: { "https://old.example": "unreachable", [SYNC]: true } });
    await h.sharing.offer("s", offer());
    expect(h.signIns()).toBe(0);
    expect(h.saved[0]).toMatchObject({ serverUrl: SYNC, candidateUrls: [SYNC], accessToken: "tok" });
  });

  it("signs in again when the host stopped sharing it before", async () => {
    const gone = jellyfinSession({ serverUrl: SYNC, serverName: "Jelly (via sync.example)", serverId: "jf-1", userId: "guest", userName: "guest", accessToken: "old", sharedBy: "sync.example", noLongerShared: true });
    const h = harness({ saved: [gone] });
    await h.sharing.offer("s", offer());
    expect(h.signIns()).toBe(1);
    expect(h.saved[0]).toMatchObject({ accessToken: "tok", noLongerShared: false });
  });
});
