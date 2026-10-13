import { describe, expect, it } from "vitest";
import * as SavedRooms from "./savedRooms";
import { keyOf, MAX_RECENT, type SavedRoom } from "./savedRooms";

// Ported from SavedRoomsTest.kt (the store operations are the same functions here).
const room = (name: string, at: number, opts: Partial<SavedRoom> = {}): SavedRoom =>
  SavedRooms.room({ host: "syncplay.pl", port: 8999, username: "Sam", room: name, lastUsedMillis: at, ...opts });

describe("SavedRooms", () => {
  it("rooms round-trip through their stored lines", () => {
    const rooms = [room("movie-night", 5, { saved: true, label: "Anime night" }), room("+club:ABCDEF123456", 3, { password: "pw\twith tab" })];
    const back = SavedRooms.decode(SavedRooms.encode(rooms));
    expect(back[0]).toEqual(rooms[0]);
    expect(back[1].password).toBe("pw with tab");
    expect(SavedRooms.decode("junk\nmore junk")).toEqual([]);
  });

  it("skipping openings is remembered with the room, and lines from before it read as off", () => {
    const old = "syncplay.pl\t8999\t1\tSam\tanime\t\tFriday\t1\t5";
    expect(SavedRooms.decode(old)[0].autoSkip).toBe(false);
    const on = room("anime", 5, { saved: true, autoSkip: true });
    expect(SavedRooms.decode(SavedRooms.encode([on]))[0].autoSkip).toBe(true);
    expect(SavedRooms.visited([on], room("anime", 9))[0].autoSkip).toBe(true);

    let rooms = SavedRooms.visited([], room("anime", 1));
    const key = keyOf(rooms[0]);
    rooms = SavedRooms.setAutoSkip(rooms, key, true);
    rooms = SavedRooms.setSaved(rooms, key, true, "Anime");
    expect(rooms[0].autoSkip).toBe(true);
    rooms = SavedRooms.setAutoSkip(rooms, key, false);
    expect(rooms[0].autoSkip).toBe(false);
  });

  it("recent rooms are newest first and capped, saved ones are kept and come first", () => {
    let rooms = [room("keep", 1, { saved: true, label: "Mine" })];
    for (let i = 1; i <= 20; i++) rooms = SavedRooms.visited(rooms, room(`r${i}`, 100 + i));
    expect(rooms[0].room).toBe("keep");
    expect(rooms).toHaveLength(1 + MAX_RECENT);
    expect(rooms[1].room).toBe("r20");
    expect(rooms.some((r) => r.room === "r1")).toBe(false);
  });

  it("rejoining keeps a saved room's name and the same room on a server is one entry", () => {
    let rooms = [room("movie-night", 1, { saved: true, label: "Anime night" })];
    rooms = SavedRooms.visited(rooms, room("movie-night", 50, { host: "SyncPlay.pl", username: "Alex" }));
    expect(rooms).toHaveLength(1);
    expect([rooms[0].label, rooms[0].saved]).toEqual(["Anime night", true]);
    expect([rooms[0].username, rooms[0].lastUsedMillis]).toEqual(["Alex", 50]);
  });

  it("saving, removing from saved and forgetting rooms", () => {
    let rooms = SavedRooms.visited([], room("a", 1));
    rooms = SavedRooms.visited(rooms, room("b", 2));
    const a = rooms.find((r) => r.room === "a")!;
    rooms = SavedRooms.setSaved(rooms, keyOf(a), true, "  Friday  ");
    expect(rooms[0].room).toBe("a");
    expect(rooms[0].label).toBe("Friday");
    rooms = SavedRooms.setSaved(rooms, keyOf(a), false);
    expect(rooms.some((r) => r.saved)).toBe(false);
    expect(rooms.find((r) => r.room === "a")!.label).toBe("");
    rooms = SavedRooms.save(rooms, room("new", 3, { label: "Typed" }));
    expect(rooms.find((r) => r.room === "new")!.saved).toBe(true);
    rooms = SavedRooms.forget(rooms, keyOf(a));
    expect(rooms.some((r) => r.room === "a")).toBe(false);
  });

  it("the web's LAN name rides at the end of the line and survives a rejoin", () => {
    const withLan = room("anime", 5, { lanName: "home-lan.duckdns.org" });
    const line = SavedRooms.encode([withLan]);
    expect(line.split("\t")).toHaveLength(11);
    expect(SavedRooms.decode(line)[0].lanName).toBe("home-lan.duckdns.org");
    expect(SavedRooms.visited([withLan], room("anime", 9))[0].lanName).toBe("home-lan.duckdns.org");
  });
});
