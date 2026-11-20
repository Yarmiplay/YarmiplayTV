import { describe, expect, it } from "vitest";
import { reportedDuration } from "./room";

describe("reportedDuration", () => {
  it("tells the room the player's length, or the server's until the player knows it", () => {
    expect(reportedDuration({ durationHint: 1400 }, 1422.5)).toBe(1422.5);
    expect(reportedDuration({ durationHint: 1400 }, 0)).toBe(1400);
    expect(reportedDuration({ durationHint: 1400, growing: false }, 1422.5)).toBe(1422.5);
  });

  it("tells the room the server's length for growing media, whose player length grows as it plays", () => {
    expect(reportedDuration({ durationHint: 0, growing: true }, 37.9)).toBe(0);
    expect(reportedDuration({ durationHint: 1500, growing: true }, 212)).toBe(1500);
  });
});
