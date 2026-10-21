import { describe, expect, it } from "vitest";
import { paletteOf, themeOf } from "./theme";

describe("themes", () => {
  it("keeps a stored theme, including Ember, Snow, Grass and Sky, and treats an unknown one as dark", () => {
    for (const key of ["dark", "noire", "monochrome", "light", "ember", "snow", "grass", "sky", "system"]) {
      expect(themeOf(key)).toBe(key);
    }
    expect(themeOf("grove")).toBe("dark");
    expect(themeOf(null)).toBe("dark");
  });

  it("maps Match the system onto Dark or Light and leaves a named theme as itself", () => {
    expect(paletteOf("system", true)).toBe("dark");
    expect(paletteOf("system", false)).toBe("light");
    expect(paletteOf("grass", false)).toBe("grass");
    expect(paletteOf("snow", true)).toBe("snow");
  });
});
