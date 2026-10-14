import { HttpSource, type ByteSource } from "../player/bytes";
import type { PlayableMedia } from "./mediaSource";

/**
 * What the room's player needs to open a server's file (the shape of sync/room.ts's NowPlaying): the stream is read
 * with Range requests, the token already in its URL, under the file's own name (what Syncplay peers compare).
 */
export interface ServerNowPlaying {
  title: string;
  fileName: string;
  size: number;
  open(): Promise<ByteSource>;
}

export function serverNowPlaying(playable: PlayableMedia): ServerNowPlaying {
  return {
    title: playable.title || playable.fileName,
    fileName: playable.fileName,
    size: playable.sizeBytes,
    open: () => HttpSource.open(playable.url, { name: playable.fileName, size: playable.sizeBytes }),
  };
}
