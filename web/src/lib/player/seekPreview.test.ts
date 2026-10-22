import { afterEach, describe, expect, it } from "vitest";
import { HttpSource } from "./bytes";
import { externalScript } from "./subtitles";
import { jellyfinSheetUrl, plexPartId, plexPreviewUrl, preferredSidecar, sidecarMatches, trickplayTile, type ExternalSubtitle, type TrickplaySet } from "../media/seekThumbs";

const set: TrickplaySet = { intervalMs: 10_000, thumbWidth: 320, thumbHeight: 180, columns: 10, rows: 10, count: 250 };

describe("seek previews and sidecar subtitles", () => {
  const original = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = original;
  });

  it("reads a stream with a range request instead of the whole file", async () => {
    const ranges: string[] = [];
    const size = 100_000_000;
    globalThis.fetch = (async (_url: string, init?: RequestInit) => {
      const range = new Headers(init?.headers).get("Range") ?? "";
      ranges.push(range);
      const match = /bytes=(\d+)-(\d+)/.exec(range);
      const start = Number(match?.[1] ?? 0);
      const end = Number(match?.[2] ?? 0);
      const body = new Uint8Array(range === "bytes=0-0" ? 1 : end - start + 1);
      return new Response(body, { status: 206, headers: { "Content-Range": `bytes ${start}-${end}/${size}` } });
    }) as typeof fetch;

    const source = await HttpSource.open("https://jelly.test/Videos/item/stream?static=true", { name: "Episode.mkv", size });
    const at = 40_000_000;
    const bytes = await source.read(at, 32);
    source.close();
    expect(bytes.byteLength).toBe(32);
    expect(ranges[0]).toBe("bytes=0-0");
    expect(ranges.length).toBeGreaterThan(1);
    for (const range of ranges.slice(1)) {
      const match = /bytes=(\d+)-(\d+)/.exec(range);
      expect(match).not.toBeNull();
      expect(Number(match?.[2])).toBeLessThan(size - 1);
    }
  });

  it("turns an external srt into ass dialogue", () => {
    const script = externalScript("1\n00:00:01,500 --> 00:00:03,000\nHello\nthere\n");
    expect(script).toContain("Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,Hello\\Nthere");
    expect(externalScript("[Script Info]\nScriptType: v4.00+\n")).toContain("[Script Info]");
  });

  it("matches a sidecar name and a trickplay tile", () => {
    expect(sidecarMatches("Episode.mkv", "Episode.en.ass")).toBe(true);
    expect(sidecarMatches("Episode.mkv", "Other.srt")).toBe(false);
    expect(trickplayTile(set, 25.4)).toMatchObject({ sheet: 0, x: 640, y: 0 });
    expect(jellyfinSheetUrl("https://jelly.test", "item-1", 320, 2, "tok")).toBe(
      "https://jelly.test/Videos/item-1/Trickplay/320/2.jpg?api_key=tok",
    );
    expect(plexPartId("/library/parts/445/file.mkv")).toBe("445");
    expect(plexPreviewUrl("http://plex.test:32400/", "445", "sd", "abc", 12.5)).toBe(
      "http://plex.test:32400/library/parts/445/indexes/sd/12500?X-Plex-Token=abc",
    );
    const subs: ExternalSubtitle[] = [
      { url: "a", title: "A", language: "en" },
      { url: "b", title: "B", language: "ja" },
    ];
    expect(preferredSidecar(subs, "jpn,ja")?.url).toBe("b");
  });
});
