import { describe, expect, it } from "vitest";
import { WebAniListClient } from "./client";

describe("WebAniListClient", () => {
  it("builds Implicit Grant authorize URL without any client_secret", () => {
    const client = new WebAniListClient("9876", () => null);
    const url = client.authorizeUrl("https://tv.yarmiplay.com/");
    expect(url).toContain("client_id=9876");
    expect(url).toContain("response_type=token");
    expect(url).not.toContain("client_secret");
  });

  it("parses access_token fragment and rejects error fragments", () => {
    const client = new WebAniListClient("9876", () => null);
    const parsed = client.parseHash("#access_token=abc123token&token_type=Bearer&expires_in=3600");
    expect(parsed?.accessToken).toBe("abc123token");
    expect(parsed!.expiresAt).toBeGreaterThan(Date.now());
    expect(client.parseHash("#error=access_denied")).toBeNull();
  });
});
