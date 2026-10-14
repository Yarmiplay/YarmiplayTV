import { FileSource, type ByteSource } from "../player/bytes";
import type { EpisodeRef } from "./audioDecoder";
import { keyOf } from "./fingerprintCache";

/** The playing episode's [number] and the [others] of its season, nearest first (the next before the one before). */
export interface Siblings {
  readonly number: number;
  readonly others: readonly EpisodeRef[];
}

/** Finds the other episodes of the playing episode ([P], however the app describes it) of its season. */
export interface SiblingFinder<P> {
  of(playing: P): Promise<Siblings | null>;
}

/** An episode file the detector can read: [EpisodeRef] without its key, which is worked out from the rest. */
export type EpisodeFields = Omit<EpisodeRef, "key">;

/** [fields] with their cache key. */
export function episodeRef(fields: EpisodeFields): EpisodeRef {
  return { ...fields, key: keyOf(fields.fileName, fields.sizeBytes, fields.url) };
}

/** By distance from [number]; of two as near, the later one. */
export function nearestFirst<E extends { readonly number: number }>(number: number, others: readonly E[]): E[] {
  return [...others].sort((x, y) => Math.abs(x.number - number) - Math.abs(y.number - number) || y.number - x.number);
}

/** Which episode of which show a file is (EpisodeInfo's part of it this needs). */
export interface EpisodeInfo {
  /** Identifies the show across its episodes: the server's series id, or the normalized title for files. */
  readonly seriesKey: string;
  readonly season: number | null;
  readonly episode: number | null;
}

/** A video on this device (the library's LocalFile has all of this). */
export interface LocalVideo {
  readonly name: string;
  readonly size: number;
  /** Where it is under its media folder, like "Show/Season 1"; empty for files directly in it. */
  readonly directory: string;
  /** Its media folder; empty for files opened or dropped on the page. */
  readonly folder: string;
  open(): Promise<Blob>;
}

/** An episode of a season on a media server. */
export interface ServerEpisode {
  readonly itemId: string;
  readonly url: string;
  readonly fileName: string;
  readonly sizeBytes: number;
  /** Seconds; 0 when not known. */
  readonly durationSeconds: number;
  readonly episode: EpisodeInfo | null;
  open(): Promise<ByteSource>;
}

/** A server's item that is the playing file. */
export interface ServerCopy {
  readonly itemId: string;
  /** For logs. */
  readonly serverName: string;
  /** The episodes of the season [itemId] is in, it among them. */
  seasonEpisodes(): Promise<ServerEpisode[]>;
}

/** The playing file, as [EpisodeSiblings] needs it. */
export interface PlayingFile {
  /** Which episode of which show it is, from a server or its file name; null for films and unknown files. */
  readonly episode: EpisodeInfo | null;
  /** The file on this device being played; null when it is streamed. */
  readonly local: LocalVideo | null;
  /** Server items that are this file. */
  readonly copies: readonly ServerCopy[];
}

/**
 * The playing episode's season (EpisodeSiblings.kt): for a file on this device, the files of the same show and season
 * (named like it, the same folder first); for a server's episode, or when this device has no others, the season on
 * the server. Nothing for files only another viewer has.
 *
 * The browser can't look beside a file opened on its own (the apps list its folder); the files opened or dropped with
 * it are among [localFiles] instead.
 */
export class EpisodeSiblings implements SiblingFinder<PlayingFile> {
  constructor(
    /** Every video on this device the page knows of: the media folders' and those opened this visit. */
    private readonly localFiles: () => readonly LocalVideo[],
    /** Which episode a file is from its name and folder (episodeFromFile); null when not an episode. */
    private readonly episodeFromFile: (fileName: string, directory: string) => EpisodeInfo | null,
  ) {}

  async of(playing: PlayingFile): Promise<Siblings | null> {
    if (playing.local) {
      const found = this.fromFiles(playing, playing.local);
      if (found) return found;
    }
    return this.fromServers(playing);
  }

  private fromFiles(playing: PlayingFile, mine: LocalVideo): Siblings | null {
    const me = playing.episode;
    const number = me?.episode;
    if (!me || number == null) return null;
    const same: [number, LocalVideo][] = [];
    for (const f of this.localFiles()) {
      if (f === mine || (f.name === mine.name && f.size === mine.size && f.folder === mine.folder && f.directory === mine.directory)) continue;
      const info = this.episodeFromFile(f.name, f.directory);
      if (!info || info.seriesKey !== me.seriesKey || info.season !== me.season) continue;
      if (info.episode != null && info.episode !== number) same.push([info.episode, f]);
    }
    // The same folder first: another folder may hold another release of the show.
    const inFolder = same.filter(([, f]) => f.folder === mine.folder && f.directory === mine.directory);
    const near = inFolder.length > 0 ? inFolder : same;
    const seen = new Set<number>();
    const others: EpisodeRef[] = [];
    for (const [n, f] of near) {
      if (seen.has(n)) continue;
      seen.add(n);
      others.push(localEpisode(n, f));
    }
    return others.length > 0 ? { number, others: nearestFirst(number, others) } : null;
  }

  private async fromServers(playing: PlayingFile): Promise<Siblings | null> {
    for (const copy of playing.copies) {
      let season: ServerEpisode[];
      try {
        season = await copy.seasonEpisodes();
      } catch (e) {
        console.warn(`EpisodeSiblings: no season from ${copy.serverName}: ${e instanceof Error ? e.message : e}`);
        continue;
      }
      const number = season.find((p) => p.itemId === copy.itemId)?.episode?.episode ?? playing.episode?.episode;
      if (number == null) continue;
      const seen = new Set<number>();
      const others: EpisodeRef[] = [];
      for (const p of season) {
        const n = p.episode?.episode;
        if (p.itemId === copy.itemId || n == null || n === number || seen.has(n)) continue;
        seen.add(n);
        others.push(
          episodeRef({
            number: n,
            url: p.url,
            fileName: p.fileName,
            sizeBytes: p.sizeBytes,
            duration: p.durationSeconds > 0 ? p.durationSeconds : null,
            remote: true,
            open: () => p.open(),
          }),
        );
      }
      if (others.length > 0) return { number, others: nearestFirst(number, others) };
    }
    return null;
  }
}

/** A made-up address for a file on this device: only a key's last resort, as its name and size are known. */
export const localUrl = (f: LocalVideo): string =>
  "file:///" + [f.folder, f.directory, f.name].filter((part) => part !== "").map(encodeURIComponent).join("/");

function localEpisode(number: number, f: LocalVideo): EpisodeRef {
  return episodeRef({
    number,
    url: localUrl(f),
    fileName: f.name,
    sizeBytes: f.size,
    duration: null,
    remote: false,
    open: async () => new FileSource(await f.open()),
  });
}
