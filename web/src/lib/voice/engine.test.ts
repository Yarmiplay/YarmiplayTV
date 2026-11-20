import { describe, expect, it } from "vitest";
import { webVoiceBitrate } from "./engine";

describe("webVoiceBitrate", () => {
  it("gives a Discord-lobby seat at the unconfigured 32 kbps default Discord's 64 kbps", () => {
    expect(webVoiceBitrate({ provider: "discord-lobby", maxBitrate: 32_000 })).toBe(64_000);
    expect(webVoiceBitrate({ maxBitrate: undefined })).toBe(64_000);
  });

  it("honours an explicit server cap and the legacy relay", () => {
    expect(webVoiceBitrate({ provider: "discord-lobby", maxBitrate: 48_000 })).toBe(48_000);
    expect(webVoiceBitrate({ provider: "yv1", maxBitrate: 32_000 })).toBe(32_000);
    expect(webVoiceBitrate({ provider: "discord-lobby", maxBitrate: 512_000 })).toBe(128_000);
  });
});
