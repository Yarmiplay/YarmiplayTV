import { describe, expect, it } from "vitest";
import { playbackDuration } from "./demuxer";

describe("playbackDuration", () => {
  it("keeps a stream length and ignores a missing container length", () => {
    expect(playbackDuration([0, 20, Number.NaN], null)).toBe(20);
    expect(playbackDuration([12], 99)).toBe(12);
  });

  it("uses the container length when every stream omits one", () => {
    expect(playbackDuration([0, Number.NaN, Number.POSITIVE_INFINITY], 20)).toBe(20);
    expect(playbackDuration([], 8.5)).toBe(8.5);
  });

  it("stays zero when nothing reports a length", () => {
    expect(playbackDuration([0, -1], null)).toBe(0);
    expect(playbackDuration([0], 0)).toBe(0);
    expect(playbackDuration([], Number.NaN)).toBe(0);
  });
});
