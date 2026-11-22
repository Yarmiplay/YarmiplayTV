import { describe, expect, it } from "vitest";
import { seconds, unsignedLowWords } from "./libav";

/** What libavjs-webcodecs-bridge does with a packet's words when libav.js isn't a global (as in the app). */
const bridgeValue = (lo: number, hi: number) => hi * 0x100000000 + lo;

describe("unsignedLowWords", () => {
  it("keeps a negative pts negative through the bridge's arithmetic", () => {
    // AAC's first packet: pts -1024, as libav.js gives it (low word signed, high word -1).
    const packet = unsignedLowWords({ pts: -1024, ptshi: -1, dts: -1024, dtshi: -1, duration: 1024, durationhi: 0 });
    expect(bridgeValue(packet.pts!, packet.ptshi)).toBe(-1024);
    expect(bridgeValue(packet.dts!, packet.dtshi)).toBe(-1024);
    expect(bridgeValue(packet.duration!, packet.durationhi)).toBe(1024);
  });

  it("leaves positive and large timestamps as they were", () => {
    const packet = unsignedLowWords({ pts: 48_000, ptshi: 0 });
    expect(bridgeValue(packet.pts!, packet.ptshi)).toBe(48_000);
    // Past 2^31 the low word is negative as libav.js gives it; the value is 2^31 + 5.
    const big = unsignedLowWords({ pts: -0x7ffffffb, ptshi: 0 });
    expect(bridgeValue(big.pts!, big.ptshi)).toBe(0x80000005);
  });

  it("agrees with seconds()", () => {
    const raw = { pts: -312, ptshi: -1 };
    const packet = unsignedLowWords(raw);
    expect(bridgeValue(packet.pts!, packet.ptshi) / 48_000).toBe(seconds(raw.pts, raw.ptshi, 1, 48_000));
  });

  it("leaves missing timestamps missing", () => {
    expect(unsignedLowWords<{ pts?: number }>({}).pts).toBeUndefined();
  });
});
