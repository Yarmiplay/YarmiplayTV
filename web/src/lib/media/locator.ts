import type { LocalFile, MatchKind as LocalMatchKind } from "../local/library";
import type { RelayFile } from "../syncplay/yarmiplay";
import { messageOf, type Found, type MatchKind, type MediaSource, type PlayableMedia } from "./mediaSource";

/** A server item that is the playing file; playback is reported to each one. */
export interface ServerCopy {
  sourceKey: string;
  itemId: string;
}

/** Where a room's playlist entry is found (MediaLocator.kt's RoomLocation). */
export type RoomLocation<R = RelayFile> =
  /** In this device's media folders (or a file opened here). */
  | { kind: "local"; file: LocalFile; matchedBy: LocalMatchKind }
  /** On a media server; [copies] are every server item that is this exact file, the streamed one included. */
  | { kind: "server"; playable: PlayableMedia; matchedBy: MatchKind; copies: ServerCopy[] }
  /** Only another viewer has it; it can be played through the YarmiplayServerTV file relay. */
  | { kind: "relay"; file: R }
  | { kind: "missing"; reason: string };

/** This device's files, as LocalLibrary.find looks them up. */
export interface LocalFiles {
  find(fileName: string): { file: LocalFile; kind: LocalMatchKind } | null;
}

/** Sizes match when both are known and equal, or when either is unknown. */
const sameSize = (a: number, b: number) => a <= 0 || b <= 0 || a === b;

/**
 * Decides which single copy of a file is opened, and finds the same file on the other servers (exact name and size
 * only) so playback can be reported to all of them (MediaLocator.kt).
 */
export class MediaLocator<R = RelayFile> {
  /** [MediaSource.key] of the server that streams a room's file when several have it exactly; empty = the first. */
  preferredServer = "";

  constructor(
    private readonly local: LocalFiles,
    /** The servers signed in to right now. */
    private readonly servers: () => readonly MediaSource[],
    /** The room's relay entry for a file name, when the Syncplay server relays files between viewers. */
    private readonly relay: (fileName: string) => R | null = () => null,
  ) {}

  get hasServers(): boolean {
    return this.servers().length > 0;
  }

  /**
   * A file someone in the room picked: local media folders first; then an exact filename on any server (the
   * preferred one breaks ties); only when no server has it exactly, a looser match (normalized name, then title
   * search) on the preferred server, then the others; last, another viewer's copy through the Syncplay server's file
   * relay.
   */
  async locateForRoom(fileName: string): Promise<RoomLocation<R>> {
    const local = this.local.find(fileName);
    if (local) return { kind: "local", file: local.file, matchedBy: local.kind };
    const servers = this.ordered();
    if (servers.length === 0) return this.relayOr(fileName, "Not in your media folders");

    const exact = (
      await Promise.all(servers.map(async (s) => ({ source: s, found: await s.findExact(fileName).catch((): Found | null => null) })))
    ).filter((r): r is { source: MediaSource; found: Found } => r.found !== null);
    if (exact.length > 0) {
      const primary = exact[0].found.playable;
      const copies = exact
        .filter(({ found }) => found.playable === primary || sameSize(found.playable.sizeBytes, primary.sizeBytes))
        .map(({ source, found }) => ({ sourceKey: source.key, itemId: found.playable.itemId }));
      return { kind: "server", playable: primary, matchedBy: "EXACT_FILENAME", copies };
    }

    const reasons: string[] = [];
    for (const s of servers) {
      const r = await s.resolveByFilename(fileName).catch((e: unknown) => ({ kind: "notFound" as const, fileName, reason: messageOf(e) || "Lookup failed" }));
      if (r.kind === "found") {
        return { kind: "server", playable: r.playable, matchedBy: r.matchedBy, copies: [{ sourceKey: s.key, itemId: r.playable.itemId }] };
      }
      reasons.push(r.reason);
    }
    return this.relayOr(fileName, reasons.join("; "));
  }

  /** Whether another viewer's copy of [fileName] can be played through the file relay. */
  hasRelay(fileName: string): boolean {
    return this.relay(fileName) !== null;
  }

  private relayOr(fileName: string, reason: string): RoomLocation<R> {
    const file = this.relay(fileName);
    return file !== null ? { kind: "relay", file } : { kind: "missing", reason };
  }

  /**
   * Every server item that is exactly [playable]'s file (same name, and same size when both are known), its own item
   * included. Looks only at the servers' filename indexes; never opens anything.
   */
  async copiesOf(playable: PlayableMedia): Promise<ServerCopy[]> {
    const others = await Promise.all(
      this.servers()
        .filter((s) => s.key !== playable.sourceKey)
        .map(async (s): Promise<ServerCopy | null> => {
          const found = await s.findExact(playable.fileName).catch(() => null);
          return found && sameSize(found.playable.sizeBytes, playable.sizeBytes) ? { sourceKey: s.key, itemId: found.playable.itemId } : null;
        }),
    );
    return [...ownCopy(playable), ...others.filter((c): c is ServerCopy => c !== null)];
  }

  /** The preferred server first, the rest in their order. */
  private ordered(): MediaSource[] {
    const list = [...this.servers()];
    return [...list.filter((s) => s.key === this.preferredServer), ...list.filter((s) => s.key !== this.preferredServer)];
  }
}

/** [playable]'s own server item, or nothing for a local file. */
export const ownCopy = (playable: Pick<PlayableMedia, "sourceKey" | "itemId">): ServerCopy[] =>
  playable.sourceKey === "" ? [] : [{ sourceKey: playable.sourceKey, itemId: playable.itemId }];
