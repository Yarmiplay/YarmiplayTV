import { describe, expect, it } from "vitest";
import { isLoopbackHost, webSocketUrl } from "./transport";

describe("isLoopbackHost", () => {
  it("is this computer by name or address", () => {
    for (const host of ["localhost", "LOCALHOST", " localhost ", "127.0.0.1", "127.1.2.3", "::1", "[::1]"]) {
      expect(isLoopbackHost(host), host).toBe(true);
    }
  });

  it("isn't another computer, even one whose name starts the same", () => {
    for (const host of ["myhome.duckdns.org", "10.100.102.10", "192.168.1.5", "localhost.example.com", "127.0.0.1.nip.io", "fe80::1", ""]) {
      expect(isLoopbackHost(host), host).toBe(false);
    }
  });
});

describe("webSocketUrl", () => {
  it("is wss:// for a server's name and ws:// for this computer", () => {
    expect(webSocketUrl("myhome.duckdns.org", 8999, true)).toBe("wss://myhome.duckdns.org:8999/yarmiplay/ws");
    expect(webSocketUrl("localhost", 8999, false)).toBe("ws://localhost:8999/yarmiplay/ws");
    expect(webSocketUrl("::1", 8999, false)).toBe("ws://[::1]:8999/yarmiplay/ws");
  });
});
