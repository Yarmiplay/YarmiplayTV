import { describe, expect, it } from "vitest";
import { fakeFetch } from "../mal/fakeFetch";
import { AniSkipClient, AniSkipError } from "./aniskip";

// Ported from AniSkipClientTest.kt (its live test, against the real AniSkip, is left out: tests never use the network).

const server = () =>
  fakeFetch((r) => {
    switch (r.url.pathname) {
      case "/v2/skip-times/21/1":
        return {
          body: {
            found: true,
            results: [
              { interval: { startTime: 1387.9, endTime: 1500 }, skipType: "ed", skipId: "b", episodeLength: 1440.4 },
              { interval: { startTime: 28.7, endTime: 118.7 }, skipType: "op", skipId: "a", episodeLength: 1439.9 },
              { interval: { startTime: 186.2, endTime: 276.2 }, skipType: "mixed-op", skipId: "c", episodeLength: 1440 },
            ],
            message: "Successfully found skip times",
            statusCode: 200,
          },
        };
      case "/v2/skip-times/21/2":
        return { body: { found: true, results: [{ interval: { startTime: 30, endTime: 120 }, skipType: "op", skipId: "d", episodeLength: 1300 }] } };
      case "/v2/skip-times/21/3":
        return { status: 500 };
      default:
        return { status: 404, body: { found: false, results: [] } };
    }
  });

describe("AniSkipClient", () => {
  it("asks for the opening and ending with the file's length, and gives them in order", async () => {
    const s = server();
    const segments = await new AniSkipClient({ fetch: s.fetch, apiUrl: "https://aniskip.example/v2" }).skipTimes(21, 1, 1440);
    expect(segments).toEqual([
      { kind: "opening", start: 28.7, end: 118.7 },
      { kind: "ending", start: 1387.9, end: 1440 },
    ]);
    const url = s.requests[0].url;
    expect(s.requests).toHaveLength(1);
    expect(url.searchParams.getAll("types[]")).toEqual(["op", "ed"]);
    expect(url.searchParams.get("episodeLength")).toBe("1440.000");
  });

  it("asks api.aniskip.com unless told otherwise", async () => {
    const s = server();
    await new AniSkipClient({ fetch: s.fetch }).skipTimes(21, 1, 1440);
    expect(s.requests[0].url.origin + s.requests[0].url.pathname).toBe("https://api.aniskip.com/v2/skip-times/21/1");
  });

  it("times measured on a file of another length are left out", async () => {
    expect(await new AniSkipClient({ fetch: server().fetch, apiUrl: "https://aniskip.example/v2" }).skipTimes(21, 2, 1440)).toEqual([]);
  });

  it("nothing known is an empty answer, and a failing AniSkip an error", async () => {
    const client = new AniSkipClient({ fetch: server().fetch, apiUrl: "https://aniskip.example/v2" });
    expect(await client.skipTimes(99, 1, 1440)).toEqual([]);
    const e = await client.skipTimes(21, 3, 1440).catch((e: unknown) => e);
    expect(e).toBeInstanceOf(AniSkipError);
    expect((e as AniSkipError).httpCode).toBe(500);
  });
});
