import type { Settings } from "../data/settings";
import type { PlayerState } from "../player/mediaPlayer";
import { FileSource, type ByteSource } from "../player/bytes";
import type { RoomLocation } from "../media";
import type { RoomState } from "../syncplay/client";
import type { RelayFile, YarmiplaySession } from "../syncplay/yarmiplay";
import * as Filenames from "../syncplay/filenames";

export type WebPreloadGate = "allowed" | "disabled" | "yield";

/** Pure gating decision so active playback always has first priority in the browser. */
export function evaluateWebPreloadGate(opts: {
  settings: Pick<Settings, "preDownloadPlaylistWifi" | "preDownloadPlaylistMobile">;
  unmetered: boolean;
  hasNowPlaying: boolean;
  player: Pick<PlayerState, "loaded" | "buffering"> | null;
  statusKind: string;
}): WebPreloadGate {
  const enabled = opts.unmetered ? opts.settings.preDownloadPlaylistWifi : opts.settings.preDownloadPlaylistMobile;
  if (!enabled) return "disabled";
  if (opts.statusKind === "resolving" || opts.statusKind === "loading") return "yield";
  if (opts.hasNowPlaying) {
    if (!opts.player || !opts.player.loaded || opts.player.buffering) return "yield";
  }
  return "allowed";
}

/** Upcoming playlist indices in priority order (next entry first), excluding currentIndex. */
export function upcomingPlaylistIndices(size: number, currentIndex: number, loopPlaylist: boolean): number[] {
  if (size <= 1 || currentIndex < 0 || currentIndex >= size) return [];
  const out: number[] = [];
  for (let i = currentIndex + 1; i < size; i++) out.push(i);
  if (loopPlaylist) {
    for (let i = 0; i < currentIndex; i++) out.push(i);
  }
  return out;
}

interface CachedEntry {
  key: string;
  fileName: string;
  size: number;
  lastUsed: number;
}

const OPFS_DIR = "ytv-preload";

function safeKey(fileName: string, size: number): string {
  let h = 2166136261;
  const raw = `${fileName.toLowerCase()}|${size}`;
  for (let i = 0; i < raw.length; i++) {
    h ^= raw.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return `p_${(h >>> 0).toString(16)}_${size}`;
}

export interface WebPreloaderDeps {
  getSettings: () => Settings;
  getRoom: () => RoomState;
  getSession: () => YarmiplaySession | null;
  getPlayerState: () => PlayerState | null;
  hasNowPlaying: () => boolean;
  getStatusKind: () => string;
  locate: (fileName: string) => Promise<RoomLocation<RelayFile>>;
  isUnmetered: () => boolean;
}

/**
 * Browser OPFS background playlist pre-downloader:
 * - Pre-downloads upcoming room playlist items at lowest priority (`?mode=prefetch&order=N` on relay,
 *   or direct HTTP fetch on Jellyfin/Plex).
 * - Immediately aborts any in-flight background transfer whenever active playback is loading or buffering.
 * - Controlled solely via Settings > Media (Wi-Fi/Ethernet default ON, mobile data default OFF).
 */
export class WebPlaylistPreloader {
  private readonly entries = new Map<string, CachedEntry>();
  private dirPromise: Promise<FileSystemDirectoryHandle | null> | null = null;
  private abort: AbortController | null = null;
  private running = false;
  private lastRoom: string | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(private readonly deps: WebPreloaderDeps) {}

  static get supported(): boolean {
    return typeof navigator !== "undefined" && typeof navigator.storage?.getDirectory === "function";
  }

  /** Called whenever room, playlist, playback state, or settings change. */
  poke() {
    if (!WebPlaylistPreloader.supported) return;
    const room = this.deps.getRoom();
    const activeRoom = room.status === "CONNECTED" ? room.room : null;
    if (activeRoom !== this.lastRoom) {
      this.lastRoom = activeRoom;
      void this.pruneUnused(activeRoom ? room.playlist : []);
    } else {
      void this.pruneUnused(room.playlist);
    }

    const gate = this.currentGate();
    if (gate !== "allowed" || room.status !== "CONNECTED" || room.playlistIndex === null) {
      this.stopActive();
      return;
    }

    if (!this.running) {
      clearTimeout(this.timer);
      this.timer = setTimeout(() => void this.runLoop(), 1500);
    }
  }

  /** Opens a completed pre-downloaded OPFS copy as a [ByteSource], or null if not cached. */
  async openComplete(fileName: string, size: number): Promise<ByteSource | null> {
    if (!WebPlaylistPreloader.supported) return null;
    const key = safeKey(fileName, size);
    let entry = this.entries.get(key);
    if (!entry && size <= 0) {
      entry = [...this.entries.values()].find((e) => Filenames.same(e.fileName, fileName));
    }
    if (!entry) return null;
    try {
      const dir = await this.getDir();
      if (!dir) return null;
      const fh = await dir.getFileHandle(entry.key);
      const file = await fh.getFile();
      if (entry.size > 0 && file.size !== entry.size) return null;
      entry.lastUsed = Date.now();
      return new FileSource(file);
    } catch {
      this.entries.delete(entry.key);
      return null;
    }
  }

  stopActive() {
    clearTimeout(this.timer);
    this.abort?.abort();
    this.abort = null;
  }

  async clearAll() {
    this.stopActive();
    const dir = await this.getDir();
    for (const key of [...this.entries.keys()]) {
      this.entries.delete(key);
      if (dir) await dir.removeEntry(key).catch(() => {});
    }
  }

  private currentGate(): WebPreloadGate {
    return evaluateWebPreloadGate({
      settings: this.deps.getSettings(),
      unmetered: this.deps.isUnmetered(),
      hasNowPlaying: this.deps.hasNowPlaying(),
      player: this.deps.getPlayerState(),
      statusKind: this.deps.getStatusKind(),
    });
  }

  private async getDir(): Promise<FileSystemDirectoryHandle | null> {
    if (!WebPlaylistPreloader.supported) return null;
    this.dirPromise ??= navigator.storage
      .getDirectory()
      .then((root) => root.getDirectoryHandle(OPFS_DIR, { create: true }))
      .catch(() => null);
    return this.dirPromise;
  }

  private async runLoop() {
    if (this.running) return;
    this.running = true;
    try {
      const dir = await this.getDir();
      if (!dir) return;
      while (this.currentGate() === "allowed") {
        const room = this.deps.getRoom();
        if (room.status !== "CONNECTED" || room.playlistIndex === null || room.playlist.length <= 1) return;
        const upcoming = upcomingPlaylistIndices(room.playlist.length, room.playlistIndex, this.deps.getSettings().sync.loopPlaylist);
        let didWork = false;
        for (let i = 0; i < upcoming.length; i++) {
          if (this.currentGate() !== "allowed") return;
          const fileName = room.playlist[upcoming[i]];
          if (!fileName || Filenames.isUrl(fileName)) continue;
          const order = i + 1;
          const loc = await this.deps.locate(fileName).catch(() => ({ kind: "missing" as const, reason: "" }));
          if (this.currentGate() !== "allowed") return;
          if (loc.kind === "local" || loc.kind === "missing") continue;

          if (loc.kind === "relay") {
            if (loc.file.media) continue; // Don't full-prefetch growing/live files
            const session = this.deps.getSession();
            if (!session) continue;
            const key = safeKey(loc.file.name, loc.file.size);
            if (this.entries.has(key)) continue;
            const cap = await this.effectiveCapBytes();
            if (loc.file.size <= 0 || loc.file.size > cap) continue;
            const mode = room.yarmiplay.capabilities.prefetch ? "prefetch" : "download";
            const url = `${session.baseUrl}/yarmiplay/files/${encodeURIComponent(loc.file.id)}?mode=${mode}&order=${order}`;
            didWork = true;
            await this.downloadToOpfs(dir, key, loc.file.name, loc.file.size, url, { Authorization: `Bearer ${session.token}` }, cap);
          } else if (loc.kind === "server") {
            const p = loc.playable;
            const key = safeKey(p.fileName, p.sizeBytes);
            if (this.entries.has(key)) continue;
            const cap = await this.effectiveCapBytes();
            if (p.sizeBytes <= 0 || p.sizeBytes > cap) continue;
            didWork = true;
            await this.downloadToOpfs(dir, key, p.fileName, p.sizeBytes, p.url, {}, cap);
          }
        }
        if (!didWork) return;
      }
    } finally {
      this.running = false;
      this.abort = null;
    }
  }

  private async downloadToOpfs(
    dir: FileSystemDirectoryHandle,
    key: string,
    fileName: string,
    expectedSize: number,
    url: string,
    headers: Record<string, string>,
    cap: number,
  ) {
    await this.trimToMakeRoom(dir, expectedSize, cap);
    const controller = new AbortController();
    this.abort = controller;
    try {
      const res = await fetch(url, { headers, signal: controller.signal });
      if (!res.ok || !res.body) return;
      const fh = await dir.getFileHandle(key, { create: true });
      const writable = await fh.createWritable();
      const reader = res.body.getReader();
      let written = 0;
      try {
        while (true) {
          if (this.currentGate() !== "allowed") {
            controller.abort();
            await writable.abort().catch(() => {});
            await dir.removeEntry(key).catch(() => {});
            return;
          }
          const { done, value } = await reader.read();
          if (done) break;
          if (value) {
            await writable.write(value);
            written += value.byteLength;
          }
        }
        await writable.close();
        if (written >= expectedSize) {
          this.entries.set(key, { key, fileName, size: expectedSize, lastUsed: Date.now() });
        } else {
          await dir.removeEntry(key).catch(() => {});
        }
      } catch {
        await writable.abort().catch(() => {});
        await dir.removeEntry(key).catch(() => {});
      }
    } catch {
      // Aborted (yielded for playback) or network error — silent and safe.
    } finally {
      if (this.abort === controller) this.abort = null;
    }
  }

  private async effectiveCapBytes(): Promise<number> {
    const settingBytes = Math.max(1, Math.min(8, this.deps.getSettings().preDownloadCacheGb || 2)) * 1024 * 1024 * 1024;
    try {
      const est = await navigator.storage?.estimate?.();
      if (est?.quota && est.usage !== undefined) {
        const free = Math.max(0, est.quota - est.usage);
        return Math.min(settingBytes, Math.max(128 * 1024 * 1024, Math.floor(free / 2)));
      }
    } catch {
      // Ignore quota estimation errors.
    }
    return settingBytes;
  }

  private async trimToMakeRoom(dir: FileSystemDirectoryHandle, incoming: number, cap: number) {
    let total = 0;
    for (const e of this.entries.values()) total += e.size;
    if (total + incoming <= cap) return;
    const ordered = [...this.entries.values()].sort((a, b) => a.lastUsed - b.lastUsed);
    for (const e of ordered) {
      if (total + incoming <= cap) break;
      this.entries.delete(e.key);
      await dir.removeEntry(e.key).catch(() => {});
      total = Math.max(0, total - e.size);
    }
  }

  private async pruneUnused(playlist: string[]) {
    const dir = await this.getDir();
    if (!dir) return;
    const keep = new Set(playlist.map((p) => p.toLowerCase()));
    for (const [k, e] of [...this.entries.entries()]) {
      if (!keep.has(e.fileName.toLowerCase())) {
        this.entries.delete(k);
        await dir.removeEntry(k).catch(() => {});
      }
    }
  }
}
