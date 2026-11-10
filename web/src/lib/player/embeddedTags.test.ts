import { describe, expect, it } from "vitest";
import { readEmbeddedTags } from "./embeddedTags";

function u32(n: number): number[] {
  return [(n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff];
}

function frame(id: string, payload: number[]): Uint8Array {
  const head = [...id].map((c) => c.charCodeAt(0));
  return Uint8Array.from([...head, ...u32(payload.length), 0, 0, ...payload]);
}

function id3(frames: Uint8Array[]): Uint8Array {
  const body = frames.reduce((all, frame) => {
    const next = new Uint8Array(all.length + frame.length);
    next.set(all);
    next.set(frame, all.length);
    return next;
  }, new Uint8Array());
  const size = body.length;
  const header = Uint8Array.from([
    0x49, 0x44, 0x33, 3, 0, 0,
    (size >> 21) & 0x7f, (size >> 14) & 0x7f, (size >> 7) & 0x7f, size & 0x7f,
  ]);
  const out = new Uint8Array(header.length + body.length);
  out.set(header);
  out.set(body, header.length);
  return out;
}

describe("embedded tags", () => {
  it("reads an ID3 title, artist and jpeg cover", () => {
    const jpeg = Uint8Array.from([0xff, 0xd8, 0xff, ...new Array(40).fill(1)]);
    const bytes = id3([
      frame("TIT2", [0, ..."Night Drive".split("").map((c) => c.charCodeAt(0))]),
      frame("TPE1", [0, ..."Ada".split("").map((c) => c.charCodeAt(0))]),
      frame("APIC", [0, ..."image/jpeg".split("").map((c) => c.charCodeAt(0)), 0, 3, 0, ...jpeg]),
    ]);
    const tags = readEmbeddedTags(bytes);
    expect(tags.title).toBe("Night Drive");
    expect(tags.artist).toBe("Ada");
    expect(tags.pictureType).toBe("image/jpeg");
    expect(tags.picture?.[0]).toBe(0xff);
    expect(tags.picture?.[1]).toBe(0xd8);
  });

  it("ignores a file with no tags", () => {
    const tags = readEmbeddedTags(Uint8Array.from(new Array(64).fill(0)));
    expect(tags.title).toBeNull();
    expect(tags.picture).toBeNull();
  });
});
