import { describe, expect, it } from "vitest";
import { joinLink, parseJoinLink } from "./joinLink";

describe("join links", () => {
  it("read the control panel's link, room decoded", () => {
    expect(parseJoinLink("#join=myhome.duckdns.org:8999/movie%20night&lan=myhome-lan.duckdns.org")).toEqual({
      host: "myhome.duckdns.org",
      port: 8999,
      room: "movie night",
      lanName: "myhome-lan.duckdns.org",
    });
    expect(parseJoinLink("#join=myhome.duckdns.org:8999/%2Bclub%3AABCDEF123456")?.room).toBe("+club:ABCDEF123456");
    expect(parseJoinLink("#join=myhome.duckdns.org:8999/")).toMatchObject({ room: "lobby", lanName: null });
  });

  it("refuse what isn't one", () => {
    expect(parseJoinLink("#/lab/player")).toBeNull();
    expect(parseJoinLink("#join=nohost")).toBeNull();
    expect(parseJoinLink("#join=h:99999/r")).toBeNull();
  });

  it("round-trip", () => {
    const l = { host: "a.duckdns.org", port: 8999, room: "שלום & more", lanName: "b.duckdns.org" };
    expect(parseJoinLink(joinLink(l).split("#")[1] ? `#${joinLink(l).split("#")[1]}` : "")).toEqual(l);
  });
});
