import { describe, expect, it } from "vitest";
import { computeOpenSubtitlesHash, SUBTITLE_LANGUAGES, WebSubtitleSearchClient } from "./search";

describe("web online subtitle search", () => {
  it("defaults to English first in SUBTITLE_LANGUAGES", () => {
    expect(SUBTITLE_LANGUAGES[0]).toEqual({ code: "en", label: "English" });
  });

  it("computes deterministic 16-hex OpenSubtitles moviehash and rejects tiny blobs", async () => {
    const tiny = new Blob([new Uint8Array(1024)]);
    expect(await computeOpenSubtitlesHash(tiny)).toBeNull();

    const buf = new Uint8Array(160 * 1024);
    for (let i = 0; i < buf.length; i++) buf[i] = i & 0xff;
    const h1 = await computeOpenSubtitlesHash(new Blob([buf]));
    const h2 = await computeOpenSubtitlesHash(new Blob([buf]));
    expect(h1).toMatch(/^[0-9a-f]{16}$/);
    expect(h1).toBe(h2);
  });

  it("makes no requests when unconfigured", async () => {
    const client = new WebSubtitleSearchClient("", "", () => "https://example.com");
    expect(client.configured).toBe(false);
    await expect(client.search({ query: "Frieren", language: "en" })).rejects.toThrow(/not configured/i);
  });
});
