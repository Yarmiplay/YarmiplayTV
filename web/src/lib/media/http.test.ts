import { describe, expect, it } from "vitest";
import { blockedAsMixedContent, buildUrl } from "./http";

describe("http", () => {
  it("builds URLs under a base with a path, encoding segments and query values", () => {
    expect(buildUrl("https://host/jellyfin/", "Items/a b", [["searchTerm", "North Wind & co"]]).href).toBe(
      "https://host/jellyfin/Items/a%20b?searchTerm=North%20Wind%20%26%20co",
    );
    expect(buildUrl("http://plex:32400", "/library/parts/7/1/file%20x.mkv", [["X-Plex-Token", "t"]], true).href).toBe(
      "http://plex:32400/library/parts/7/1/file%20x.mkv?X-Plex-Token=t",
    );
    expect(() => buildUrl("not a url", "x")).toThrow("Invalid server address: not a url");
  });

  it("an https page can't reach plain http, except this computer's own loopback", () => {
    expect(blockedAsMixedContent("http://192.168.1.5:8096", "https:")).toBe(true);
    expect(blockedAsMixedContent("https://192.168.1.5:8920", "https:")).toBe(false);
    expect(blockedAsMixedContent("http://192.168.1.5:8096", "http:")).toBe(false);
    expect(blockedAsMixedContent("http://localhost:8096", "https:")).toBe(false);
    expect(blockedAsMixedContent("http://127.0.0.1:8096", "https:")).toBe(false);
  });
});
