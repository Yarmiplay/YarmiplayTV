import { describe, expect, it } from "vitest";
import { decodeKeyBase64, open, parseTokenHex, seal, YV1 } from "./yv1";

describe("yv1 packets", () => {
  it("parses lower-case token hex as big-endian u64", () => {
    expect(parseTokenHex("0123456789abcdef")).toBe(0x0123456789abcdefn);
    expect(parseTokenHex("0123456789ABCDEF")).toBeNull();
    expect(parseTokenHex("short")).toBeNull();
  });

  it("seals and opens an audio frame", async () => {
    const key = crypto.getRandomValues(new Uint8Array(32));
    const plain = new Uint8Array([1, 2, 3, 4, 9]);
    const packet = await seal(key, YV1.TYPE_AUDIO, 0x0123456789abcdefn, 0n, 7, 960, plain);
    expect(packet.length).toBeGreaterThanOrEqual(YV1.MIN_PACKET);
    expect(packet.length).toBeLessThanOrEqual(YV1.MAX_PACKET);
    expect(packet[0]).toBe(0x59);
    expect(packet[1]).toBe(0x56);
    const opened = await open(key, packet);
    expect(opened).not.toBeNull();
    expect(opened!.type).toBe(YV1.TYPE_AUDIO);
    expect(opened!.sequence).toBe(7);
    expect(opened!.timestamp).toBe(960);
    expect([...opened!.plain]).toEqual([...plain]);
  });

  it("rejects a wrong key", async () => {
    const key = crypto.getRandomValues(new Uint8Array(32));
    const other = crypto.getRandomValues(new Uint8Array(32));
    const packet = await seal(key, YV1.TYPE_KEEPALIVE, 1n, 0n, 1, 0, new Uint8Array([0]));
    expect(await open(other, packet)).toBeNull();
  });

  it("decodes a 32-byte base64 key", () => {
    const raw = new Uint8Array(32).fill(7);
    const b64 = btoa(String.fromCharCode(...raw));
    expect([...decodeKeyBase64(b64)!]).toEqual([...raw]);
    expect(decodeKeyBase64("@@@")).toBeNull();
  });
});
