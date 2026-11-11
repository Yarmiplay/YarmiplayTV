import { describe, expect, it } from "vitest";
import { approach, barsFromFrequency, idle, isAttachedPicture, isMotionVideo, showSpectrum } from "./spectrum";

describe("spectrum", () => {
  it("holds the frame when time does not move", () => {
    const frame = new Float32Array([0.2, 0.8, 0.4]);
    const held = approach(frame, [1, 1, 1], 0, 3);
    held.forEach((value, i) => expect(value).toBeCloseTo(frame[i], 5));
    const moved = approach(frame, [1, 1, 1], 1, 3);
    expect(moved[0]).toBeGreaterThan(0.2);
    expect(moved[0]).toBeLessThanOrEqual(1);
  });

  it("reads peaks from the real spectrum and ignores a silent file", () => {
    const freq = new Uint8Array(64);
    expect(Array.from(barsFromFrequency(freq, 8)).every((v) => v === 0)).toBe(true);
    freq[4] = 255;
    const bars = barsFromFrequency(freq, 8);
    expect(Math.max(...bars)).toBeGreaterThan(0.9);
    expect(bars.some((v) => v === 0)).toBe(true);
  });

  it("treats cover art as audio and a real video track as video", () => {
    expect(isAttachedPicture("mjpeg")).toBe(true);
    expect(isAttachedPicture("PNG")).toBe(true);
    expect(isAttachedPicture("webp")).toBe(true);
    expect(isMotionVideo("video", "mjpeg")).toBe(false);
    expect(isMotionVideo("video", "h264")).toBe(true);
    expect(isMotionVideo("audio", "aac")).toBe(false);
    expect(showSpectrum(true, false, true)).toBe(true);
    expect(showSpectrum(true, true, true)).toBe(false);
    expect(showSpectrum(false, false, true)).toBe(false);
    expect(showSpectrum(true, false, false)).toBe(false);
  });

  it("keeps the idle bars in range and moves them with the phase", () => {
    const a = idle(0.2);
    const b = idle(1.4);
    expect(a.length).toBe(48);
    expect(Array.from(a).every((v) => v >= 0.04 && v <= 1)).toBe(true);
    expect(Array.from(a).join()).not.toBe(Array.from(b).join());
  });
});
