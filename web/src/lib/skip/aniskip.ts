import type { SkipKind, SkipSegment } from "./segment";

/** `fetch` as the client uses it; tests pass their own. */
export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>;

/** An AniSkip request that failed: [httpCode] is the answer's status, or null when there was no answer. */
export class AniSkipError extends Error {
  constructor(
    message: string,
    readonly httpCode: number | null = null,
  ) {
    super(message);
    this.name = "AniSkipError";
  }
}

/** Times measured on a file more than this many seconds longer or shorter don't fit this one. */
export const LENGTH_TOLERANCE = 5;

/**
 * Where an anime episode's opening and ending are, from AniSkip (api.aniskip.com: open source, timestamps sent in by
 * its users, no account or key, and it allows browsers' cross-origin requests). Asked with the MyAnimeList id, the
 * episode number and the file's length, nothing else.
 */
export class AniSkipClient {
  private readonly fetch: FetchLike;
  private readonly apiUrl: string;

  constructor(options: { fetch?: FetchLike; apiUrl?: string } = {}) {
    this.fetch = options.fetch ?? ((url, init) => globalThis.fetch(url, init));
    this.apiUrl = (options.apiUrl ?? "https://api.aniskip.com/v2").replace(/\/+$/, "");
  }

  /**
   * The opening and ending of episode [episode] of MyAnimeList anime [malId], for a file [lengthSeconds] long;
   * empty when AniSkip knows none. AniSkip picks the times measured on the closest-length file; times measured
   * on a file of another length (a different cut) are left out, as are any outside the file.
   */
  async skipTimes(malId: number, episode: number, lengthSeconds: number, signal?: AbortSignal): Promise<SkipSegment[]> {
    const q = new URLSearchParams([
      ["types[]", "op"],
      ["types[]", "ed"],
      ["episodeLength", lengthSeconds.toFixed(3)],
    ]);
    let response: Response;
    try {
      response = await this.fetch(`${this.apiUrl}/skip-times/${malId}/${episode}?${q}`, { headers: { Accept: "application/json" }, signal });
    } catch (e) {
      if (signal?.aborted) throw e;
      throw new AniSkipError(`Cannot reach AniSkip (${e instanceof Error ? e.message : String(e)})`);
    }
    if (response.status === 404) return [];
    if (!response.ok) throw new AniSkipError(`AniSkip answered ${response.status}`, response.status);
    let answer: unknown;
    try {
      answer = JSON.parse(await response.text());
    } catch {
      throw new AniSkipError("AniSkip sent something unreadable");
    }
    if (typeof answer !== "object" || answer === null) throw new AniSkipError("AniSkip sent something unreadable");
    const { found, results } = answer as { found?: unknown; results?: unknown };
    if (found !== true || !Array.isArray(results)) return [];
    return results
      .map((r) => segment(r, lengthSeconds))
      .filter((s): s is SkipSegment => s != null)
      .sort((a, b) => a.start - b.start);
  }
}

/** One of AniSkip's results as a part of this file, or null when it's another kind, another cut or outside the file. */
function segment(r: unknown, length: number): SkipSegment | null {
  if (typeof r !== "object" || r === null) return null;
  const { interval, skipType, episodeLength } = r as { interval?: unknown; skipType?: unknown; episodeLength?: unknown };
  if (typeof interval !== "object" || interval === null) return null;
  const { startTime, endTime } = interval as { startTime?: unknown; endTime?: unknown };
  if (typeof startTime !== "number" || typeof endTime !== "number") return null;
  let kind: SkipKind;
  if (skipType === "op") kind = "opening";
  else if (skipType === "ed") kind = "ending";
  else return null;
  const measured = typeof episodeLength === "number" ? episodeLength : 0;
  if (length > 0 && measured > 0 && Math.abs(measured - length) > LENGTH_TOLERANCE) return null;
  const start = Math.max(startTime, 0);
  const end = length > 0 ? Math.min(endTime, length) : endTime;
  if (end - start < 1 || (length > 0 && start >= length)) return null;
  return { kind, start, end };
}
