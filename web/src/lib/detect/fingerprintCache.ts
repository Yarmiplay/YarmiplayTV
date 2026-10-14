import type { CacheStore } from "./cacheStore";
import { Mutex } from "./mutex";
import { sha1Hex } from "./sha1";

/** Which part of an episode: its opening or its ending (SkipSegment.Kind's names). */
export type SkipKind = "OPENING" | "ENDING";

/** Where a shared stretch of sound is in one episode, in seconds. */
export interface Span {
  readonly start: number;
  readonly end: number;
}

/** A fingerprint of the sound from [start] seconds into the episode. */
export interface StoredFingerprint {
  readonly start: number;
  readonly points: Int32Array;
}

/** By kind, then by the other episode's key. */
type Observations = Record<string, Record<string, Span[]>>;

const MAGIC = 0x59465031; // "YFP1"
/** The cache is kept under this many bytes. */
export const MAX_BYTES = 64 * 1024 * 1024;

/** Who an episode file is: its name and size when known (the same file anywhere), else its address. */
export function keyOf(fileName: string, sizeBytes: number, url: string): string {
  const query = url.indexOf("?");
  const identity = sizeBytes > 0 && fileName.trim() !== "" ? `${fileName.toLowerCase()}|${sizeBytes}` : query < 0 ? url : url.slice(0, query);
  return sha1Hex(new TextEncoder().encode(`v1|${identity}`));
}

/**
 * A fingerprint as the apps keep it ("YFP1"): the magic number, the start in seconds as a double, the number of
 * points, then the points, all big-endian.
 */
export function encodeFingerprint(stored: StoredFingerprint): Uint8Array {
  const bytes = new Uint8Array(16 + stored.points.length * 4);
  const view = new DataView(bytes.buffer);
  view.setInt32(0, MAGIC);
  view.setFloat64(4, stored.start);
  view.setInt32(12, stored.points.length);
  for (let i = 0; i < stored.points.length; i++) view.setInt32(16 + i * 4, stored.points[i]);
  return bytes;
}

/** A fingerprint read back from [encodeFingerprint]'s bytes; null when they aren't one; throws when they are cut short. */
export function decodeFingerprint(bytes: Uint8Array): StoredFingerprint | null {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (view.getInt32(0) !== MAGIC) return null;
  const start = view.getFloat64(4);
  const count = view.getInt32(12);
  if (count < 0 || 16 + count * 4 > bytes.length) throw new RangeError("Fingerprint cut short");
  const points = new Int32Array(count);
  for (let i = 0; i < count; i++) points[i] = view.getInt32(16 + i * 4);
  return { start, points };
}

/** A number as kotlinx.serialization writes a Double: whole numbers with ".0", so the files read the same as the apps'. */
function kotlinDouble(x: number): string {
  return Number.isInteger(x) && Math.abs(x) < 1e7 ? x.toFixed(1) : JSON.stringify(x);
}

/** [observations] as the apps write them: {"OPENING":{"<other key>":[[start,end],...]}}. */
function encodeObservations(observations: Observations): string {
  const kinds = Object.entries(observations).map(([kind, byOther]) => {
    const others = Object.entries(byOther).map(
      ([other, spans]) => `${JSON.stringify(other)}:[${spans.map((s) => `[${kotlinDouble(s.start)},${kotlinDouble(s.end)}]`).join(",")}]`,
    );
    return `${JSON.stringify(kind)}:{${others.join(",")}}`;
  });
  return `{${kinds.join(",")}}`;
}

function decodeObservations(text: string): Observations {
  const json: unknown = JSON.parse(text);
  if (typeof json !== "object" || json === null || Array.isArray(json)) throw new TypeError("Not an object");
  const out: Observations = {};
  for (const [kind, byOther] of Object.entries(json as Record<string, unknown>)) {
    if (typeof byOther !== "object" || byOther === null || Array.isArray(byOther)) throw new TypeError("Not an object");
    out[kind] = {};
    for (const [other, list] of Object.entries(byOther as Record<string, unknown>)) {
      if (!Array.isArray(list)) throw new TypeError("Not a list");
      out[kind][other] = list.map((pair: unknown) => {
        if (!Array.isArray(pair) || typeof pair[0] !== "number" || typeof pair[1] !== "number") throw new TypeError("Not a span");
        return { start: pair[0], end: pair[1] };
      });
    }
  }
  return out;
}

/**
 * What listening for openings and endings has learned, kept in [store] so it's never done twice (FingerprintCache.kt):
 * each episode's fingerprints (its start and its end), and for each pair of episodes compared, the stretches they share
 * (none, too). Episodes are known by file name and size, so a file on this device and the same file on a server are
 * one. Kept under [MAX_BYTES]; the least recently used go first. One thing is done at a time, as the Kotlin's are
 * synchronized: remembering an observation reads the file, adds to it and writes it back.
 */
export class FingerprintCache {
  private readonly busy = new Mutex();
  private pruned = false;

  constructor(
    private readonly store: CacheStore,
    private readonly maxBytes = MAX_BYTES,
  ) {}

  fingerprint(key: string, kind: SkipKind): Promise<StoredFingerprint | null> {
    return this.busy.lock(async () => {
      const path = fingerprintPath(key, kind);
      try {
        const bytes = await this.store.read(path);
        if (!bytes) return null;
        const stored = decodeFingerprint(bytes);
        if (!stored) return null;
        await this.store.touch(path);
        return stored;
      } catch (e) {
        console.warn(`FingerprintCache: unreadable fingerprint ${path}`, e);
        await this.store.remove(path).catch(() => {});
        return null;
      }
    });
  }

  saveFingerprint(key: string, kind: SkipKind, stored: StoredFingerprint): Promise<void> {
    return this.busy.lock(async () => {
      if (!this.pruned) {
        this.pruned = true;
        await this.prune().catch((e) => console.warn("FingerprintCache: can't prune", e));
      }
      await this.write(fingerprintPath(key, kind), encodeFingerprint(stored));
    });
  }

  /** For episode [key], what each other episode compared with it shares with it, by that episode's key. */
  observations(key: string, kind: SkipKind): Promise<Record<string, Span[]>> {
    return this.busy.lock(async () => (await this.read(key))[kind] ?? {});
  }

  /** Remembers that episode [key] shares [spans] (its own times) with episode [other]. */
  observe(key: string, other: string, kind: SkipKind, spans: readonly Span[]): Promise<void> {
    return this.busy.lock(async () => {
      const now = await this.read(key);
      const next: Observations = { ...now, [kind]: { ...(now[kind] ?? {}), [other]: spans.map((s) => ({ start: s.start, end: s.end })) } };
      await this.write(observationsPath(key), new TextEncoder().encode(encodeObservations(next)));
    });
  }

  private async read(key: string): Promise<Observations> {
    const path = observationsPath(key);
    try {
      const bytes = await this.store.read(path);
      if (!bytes) return {};
      const observations = decodeObservations(new TextDecoder().decode(bytes));
      await this.store.touch(path);
      return observations;
    } catch {
      await this.store.remove(path).catch(() => {});
      return {};
    }
  }

  private async write(path: string, data: Uint8Array): Promise<void> {
    try {
      await this.store.write(path, data);
    } catch (e) {
      console.warn(`FingerprintCache: can't save ${path}`, e);
    }
  }

  /** Down to three quarters of [maxBytes] when over it, least recently used first. */
  private async prune(): Promise<void> {
    const files = [...(await this.store.list("fp")), ...(await this.store.list("obs"))];
    let total = files.reduce((sum, f) => sum + f.size, 0);
    if (total <= this.maxBytes) return;
    for (const f of files.sort((x, y) => x.lastUsed - y.lastUsed)) {
      if (total <= (this.maxBytes * 3) / 4) break;
      total -= f.size;
      await this.store.remove(f.path);
    }
  }
}

const fingerprintPath = (key: string, kind: SkipKind) => `fp/${key}-${kind === "OPENING" ? "op" : "ed"}.bin`;
const observationsPath = (key: string) => `obs/${key}.json`;
