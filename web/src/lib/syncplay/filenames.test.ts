/// <reference types="node" />
import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import * as Filenames from "./filenames";
import * as FileMatch from "./fileMatch";
import { md5Hex, sha256Hex } from "./hash";
import * as ManagedRooms from "./managedRooms";
import { formatTime, type FileInfo } from "./models";

// Ported from FileMatchTest.kt, PlaylistLogicTest.kt and ManagedRoomsTest.kt.
const ep1 = "Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv";
const mine: FileInfo = { name: ep1, duration: 1420, size: 587443083 };

describe("hashes", () => {
  it("match known digests", () => {
    expect(sha256Hex("")).toBe("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    expect(sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    expect(sha256Hex("a".repeat(1000))).toBe("41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3");
    expect(md5Hex("")).toBe("d41d8cd98f00b204e9800998ecf8427e");
    expect(md5Hex("The quick brown fox jumps over the lazy dog")).toBe("9e107d9d372bb6826bd81d3542a419d6");
  });

  it("agree with Node's crypto on any text, at every padding length", () => {
    for (let n = 0; n < 140; n++) {
      const text = "שלום-é-🎬".repeat(n % 5) + "x".repeat(n);
      expect(sha256Hex(text)).toBe(createHash("sha256").update(text, "utf8").digest("hex"));
      expect(md5Hex(text)).toBe(createHash("md5").update(text, "utf8").digest("hex"));
    }
  });

  it("match Syncplay's utils", () => {
    // hashlib.sha256(...).hexdigest()[:12] of the stripped name and of str(size), as Syncplay sends them.
    expect(Filenames.hash(ep1)).toBe("fd0cfaa80c3c");
    expect(Filenames.hashSize(587443083)).toBe("194298ffe18f");
  });
});

describe("Filenames", () => {
  it("hashed and hidden names match the plain name", () => {
    expect(Filenames.same(ep1, "fd0cfaa80c3c")).toBe(true);
    expect(Filenames.same("fd0cfaa80c3c", ep1)).toBe(true);
    expect(Filenames.same(ep1, Filenames.HIDDEN)).toBe(true);
    expect(Filenames.same(ep1.replace("S01E01", "S01E02"), "fd0cfaa80c3c")).toBe(false);
  });

  it("same filename ignores separators and brackets like Syncplay", () => {
    expect(Filenames.same(ep1, "Hyperdimension_Neptunia_S01E01_The_Goddess_Neptune_Of_Planeptune.mkv")).toBe(true);
    expect(
      Filenames.same(
        ep1,
        "http://10.0.2.2:8096/files/Hyperdimension%20Neptunia%20-%20S01E01%20-%20The%20Goddess%20(Neptune)%20Of%20Planeptune.mkv",
      ),
    ).toBe(true);
    expect(Filenames.same(ep1, ep1.replace("S01E01", "S01E02"))).toBe(false);
    expect(Filenames.same(ep1, ep1.toUpperCase())).toBe(true);
    expect(Filenames.same(null, ep1)).toBe(false);
  });

  it("base name handles windows and posix paths", () => {
    expect(Filenames.baseName(`F:\\Torrents\\Neptunia\\Season 1\\${ep1}`)).toBe(ep1);
    expect(Filenames.baseName(`/media/anime/Season 1/${ep1}`)).toBe(ep1);
  });
});

describe("FileMatch", () => {
  it("sizes compare plainly or by hash, and unknown sizes match", () => {
    expect(FileMatch.differences(mine, { ...mine, name: "fd0cfaa80c3c" })).toEqual(new Set());
    expect(FileMatch.differences(mine, { ...mine, size: 587443084 })).toEqual(new Set(["SIZE"]));
    expect(FileMatch.sameSize(mine, { ...mine, size: 0, sizeHash: "194298ffe18f" })).toBe(true);
    expect(FileMatch.sameSize(mine, { ...mine, size: 0, sizeHash: Filenames.hashSize(1) })).toBe(false);
    expect(FileMatch.sameSize(mine, { ...mine, size: 0 })).toBe(true);
    const hashed = { ...mine, size: 0, sizeHash: "194298ffe18f" };
    expect(FileMatch.sameSize(hashed, hashed)).toBe(true);
  });

  it("durations within the threshold match", () => {
    expect(FileMatch.sameDuration(mine, { ...mine, duration: 1421.9 })).toBe(true);
    expect(FileMatch.differences(mine, { name: "other.mkv", duration: 1300, size: 587443083 })).toEqual(
      new Set(["NAME", "DURATION"]),
    );
  });
});

describe("ManagedRooms", () => {
  const managed = "+movie night:D6A85289CDB5";

  it("room names and passwords", () => {
    expect(ManagedRooms.isManaged(managed)).toBe(true);
    expect(ManagedRooms.display(managed)).toBe("movie night");
    expect(ManagedRooms.label(managed)).toBe("🔒 movie night");
    // A colon inside the base name is part of it; only the last :HASH counts.
    expect(ManagedRooms.display("+a:b:0123456789AB")).toBe("a:b");
    // Wrong hash lengths, a missing +, or no hash: ordinary names, shown as they are.
    for (const name of ["+movie night:D6A85289CDB", "+movie night:D6A85289CDB51", "movie night:D6A85289CDB5", "+movie night", "lobby"]) {
      expect(ManagedRooms.isManaged(name)).toBe(false);
      expect(ManagedRooms.display(name)).toBe(name);
      expect(ManagedRooms.label(name)).toBe(name);
    }
    for (let i = 0; i < 50; i++) expect(ManagedRooms.isValidPassword(ManagedRooms.generatePassword())).toBe(true);
    expect(ManagedRooms.isValidPassword("ab-123-456")).toBe(false);
    expect(ManagedRooms.normalizePassword(" ab-123-456\n")).toBe("AB-123-456");
  });

  it("base names", () => {
    expect(ManagedRooms.isValidBaseName("")).toBe(false);
    expect(ManagedRooms.isValidBaseName("x".repeat(22))).toBe(false);
    expect(ManagedRooms.isValidBaseName("x".repeat(21))).toBe(true);
  });
});

it("format time", () => {
  expect(formatTime(65.4)).toBe("01:05");
  expect(formatTime(3600)).toBe("1:00:00");
});
