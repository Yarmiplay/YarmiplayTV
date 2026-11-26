import { describe, expect, it } from "vitest";
import { chatParts, formatChatTimestamp } from "./links";

describe("chat links", () => {
  it("links http, https, and www, and leaves the rest selectable text", () => {
    const parts = chatParts("see https://example.com/a. and www.example.com please");
    expect(parts).toEqual([
      { text: "see " },
      { text: "https://example.com/a", href: "https://example.com/a" },
      { text: ". and " },
      { text: "www.example.com", href: "https://www.example.com" },
      { text: " please" },
    ]);
  });

  it("keeps a balanced parenthesis and drops a wrapping one", () => {
    expect(chatParts("https://en.wikipedia.org/wiki/Foo_(bar)")).toEqual([
      { text: "https://en.wikipedia.org/wiki/Foo_(bar)", href: "https://en.wikipedia.org/wiki/Foo_(bar)" },
    ]);
    expect(chatParts("(https://example.com)")).toEqual([
      { text: "(" },
      { text: "https://example.com", href: "https://example.com" },
      { text: ")" },
    ]);
  });

  it("does not link an email, a bare scheme, or the middle of a word", () => {
    expect(chatParts("a@b.com and http:// and https:// and www.")).toEqual([
      { text: "a@b.com and http:// and https:// and www." },
    ]);
    expect(chatParts("nothttps://example.com")).toEqual([{ text: "nothttps://example.com" }]);
  });

  it("finds playback timestamps without splitting urls or invalid times", () => {
    const parts = chatParts("look @14:22 and 1:05:10 not https://example.com/12:34 or 12:99");
    expect(parts.filter((p) => p.timestampSeconds !== undefined)).toEqual([
      { text: "@14:22", timestampSeconds: 862 },
      { text: "1:05:10", timestampSeconds: 3910 },
    ]);
    expect(parts.filter((p) => p.href !== undefined)).toEqual([
      { text: "https://example.com/12:34", href: "https://example.com/12:34" },
    ]);
    expect(formatChatTimestamp(862)).toBe("14:22");
    expect(formatChatTimestamp(3910)).toBe("1:05:10");
  });
});
