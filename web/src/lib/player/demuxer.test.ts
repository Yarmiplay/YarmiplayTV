import { describe, expect, it } from "vitest";
import type { ByteSource, GrowingByteSource } from "./bytes";
import { Demuxer } from "./demuxer";
import type { LibAV } from "./libav";

/** Just enough of libav.js to open a file without streams, with every seek and close it is asked for recorded. */
function fakeLibAV(calls: unknown[][]): LibAV {
  return {
    AVSEEK_FLAG_BACKWARD: 1,
    AVSEEK_FLAG_BYTE: 2,
    AVERROR_EOF: -541478725,
    EAGAIN: 11,
    AV_PKT_FLAG_KEY: 1,
    mkblockreaderdev: async (name: string, size: number) => void calls.push(["device", size]),
    ff_init_demuxer_file: async () => [7, []],
    ff_get_demuxer_chapters: async () => [],
    AVFormatContext_duration: async () => 900_000_000,
    AVFormatContext_durationhi: async () => 0,
    AVFormatContext_start_time: async () => 1_400_000,
    AVFormatContext_start_timehi: async () => 0,
    av_packet_alloc: async () => 9,
    av_seek_frame: async (...args: number[]) => {
      calls.push(["av_seek_frame", ...args]);
      return 0;
    },
    avformat_seek_file_max: async (...args: number[]) => {
      calls.push(["avformat_seek_file_max", ...args]);
      return 0;
    },
    av_packet_free_js: async () => {},
    avformat_close_input_js: async () => void calls.push(["close"]),
    unlink: async () => {},
  } as unknown as LibAV;
}

class FakeGrowing implements GrowingByteSource {
  readonly growingSource = true as const;
  readonly name = "part";
  readonly size = 10 * 1024 ** 3;
  readonly ended = false;
  readonly asked: number[] = [];
  constructor(private readonly calls: unknown[][]) {}
  read = async () => new Uint8Array(0);
  close() {}
  playableDuration = () => 42;
  clampSeconds = (s: number) => s;
  seekPoint(seconds: number) {
    this.asked.push(seconds);
    return { offset: 5_000_000_000, time: 10 };
  }
  onChange = () => () => {};
  abort = () => void this.calls.push(["abort"]);
}

describe("Demuxer with a growing source", () => {
  it("takes the length from the index and seeks to the byte where the segment starts", async () => {
    const calls: unknown[][] = [];
    const source = new FakeGrowing(calls);
    const demuxer = await Demuxer.open(fakeLibAV(calls), source);
    expect(demuxer.duration).toBe(42);
    expect(demuxer.startTime).toBeCloseTo(1.4);
    expect(calls).toContainEqual(["device", 10 * 1024 ** 3]);

    await demuxer.seek(1.4 + 12);
    expect(source.asked[0]).toBeCloseTo(12);
    expect(calls).toContainEqual(["av_seek_frame", 7, -1, 5_000_000_000 % 0x100000000 | 0, 1, 2]);
    expect(calls.some((c) => c[0] === "avformat_seek_file_max")).toBe(false);

    await demuxer.close();
    expect(calls.findIndex((c) => c[0] === "abort")).toBeLessThan(calls.findIndex((c) => c[0] === "close"));
  });

  it("a plain source still seeks by time and keeps the container's length", async () => {
    const calls: unknown[][] = [];
    const source: ByteSource = { name: "file", size: 1000, read: async () => new Uint8Array(0), close() {} };
    const demuxer = await Demuxer.open(fakeLibAV(calls), source);
    expect(demuxer.duration).toBe(900);
    await demuxer.seek(30);
    expect(calls).toContainEqual(["avformat_seek_file_max", 7, -1, 30_000_000, 0, 0]);
    expect(calls.some((c) => c[0] === "av_seek_frame")).toBe(false);
    expect(calls.some((c) => c[0] === "abort")).toBe(false);
  });
});
