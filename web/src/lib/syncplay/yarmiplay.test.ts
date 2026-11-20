import { describe, expect, it } from "vitest";
import { parseCapabilities, parseRelayFile } from "./client";
import { comparableSize, noCapabilities, parseGrowingMedia, parseSegmentPage } from "./yarmiplay";

const growingEntry = {
  id: "g1",
  name: "Part 1",
  size: 10737418240,
  duration: 0,
  quickHash: "h",
  sources: 1,
  cachedBytes: 5000000,
  rate: 0,
  media: { growing: true, live: true, contentType: "video/mp2t", length: 5000000 },
};

describe("growing relay files", () => {
  it("the capability is off unless the server says so", () => {
    expect(noCapabilities().growingFiles).toBe(false);
    expect(parseCapabilities({ fileRelay: true })!.growingFiles).toBe(false);
    expect(parseCapabilities({ fileRelay: true, growingFiles: true })!.growingFiles).toBe(true);
  });

  it("an entry's media is read only with the capability", () => {
    expect(parseRelayFile(growingEntry)).not.toHaveProperty("media");
    expect(parseRelayFile(growingEntry, false)).not.toHaveProperty("media");
    expect(parseRelayFile(growingEntry, true)!.media).toEqual({ growing: true, live: true, contentType: "video/mp2t", length: 5000000 });
    const { media: _, ...plain } = growingEntry;
    expect(parseRelayFile(plain, true)).not.toHaveProperty("media");
  });

  it("media fields default safely", () => {
    expect(parseGrowingMedia(null)).toBeNull();
    expect(parseGrowingMedia([1])).toBeNull();
    expect(parseGrowingMedia({})).toEqual({ growing: false, live: false, contentType: "application/octet-stream", length: 0 });
    expect(parseGrowingMedia({ growing: "yes", length: -5, contentType: "" })).toEqual({
      growing: false,
      live: false,
      contentType: "application/octet-stream",
      length: 0,
    });
  });

  it("a growing entry's size is never a larger copy", () => {
    expect(comparableSize(parseRelayFile(growingEntry, true)!)).toBe(0);
    expect(comparableSize(parseRelayFile(growingEntry, false)!)).toBe(10737418240);
  });

  it("reads a segment page, stopping at the first malformed entry", () => {
    expect(parseSegmentPage({ growing: true })).toBeNull();
    expect(parseSegmentPage("x")).toBeNull();
    expect(
      parseSegmentPage({
        growing: true,
        live: true,
        length: 3000,
        from: 4,
        segments: [
          [0, 1000, 2.002],
          [1000, 900, 2],
          [1900, 0, 2],
          [2900, 100, 1],
        ],
      }),
    ).toEqual({
      growing: true,
      live: true,
      length: 3000,
      from: 4,
      segments: [
        { offset: 0, length: 1000, duration: 2.002 },
        { offset: 1000, length: 900, duration: 2 },
      ],
    });
    expect(parseSegmentPage({ segments: [] })).toEqual({ growing: false, live: false, length: 0, from: 0, segments: [] });
  });
});
