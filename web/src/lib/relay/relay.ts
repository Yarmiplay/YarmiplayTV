import type { LocalFile, LocalLibrary } from "../local/library";
import { find } from "../local/library";
import { HttpSource } from "../player/bytes";
import type { RoomState, RoomUser, SyncplayClient } from "../syncplay/client";
import { relayActive } from "../syncplay/client";
import * as Filenames from "../syncplay/filenames";
import type { FileInfo } from "../syncplay/models";
import { isReadable, quickHash, Yarmiplay, type RelayFile, type RelayOffer, type UploadRequest, type YarmiplaySession } from "../syncplay/yarmiplay";

/**
 * YarmiplayServerTV's file relay for the browser (RelayOffers.kt, RelayUploader.kt and RelayManager.find): this
 * device offers the playlist's entries it has, serves the server's upload requests for them, and plays another
 * viewer's copy through the server when it has none of its own.
 */

/** The session as the relay reads it on every YarmiplayServerTV version (1.8 also takes X-Yarmiplay-Session). */
const auth = (session: YarmiplaySession) => ({ Authorization: `Bearer ${session.token}` });

/**
 * The playlist entries this device has, as relay offers named like the playlist entry (so viewers match them by
 * name), with the local file each one reads. Durations come from what we or the room reported.
 */
export async function offersFor(
  playlist: string[],
  files: LocalFile[],
  users: RoomUser[],
  reported: FileInfo | null,
  hash: (file: LocalFile) => Promise<string | null>,
): Promise<[RelayOffer, LocalFile][]> {
  const out: [RelayOffer, LocalFile][] = [];
  const seen = new Set<string>();
  for (const entry of playlist) {
    if (out.length >= Yarmiplay.MAX_OFFERED_FILES) break;
    if (Filenames.isUrl(entry)) continue;
    const name = Filenames.baseName(entry);
    if (seen.has(name)) continue;
    seen.add(name);
    const file = find(files, entry)?.file;
    if (!file || file.size <= 0) continue;
    const quickHash = await hash(file);
    if (!quickHash) continue;
    const duration =
      (reported && (reported.name === name || reported.name === file.name) && reported.duration > 0 ? reported.duration : 0) ||
      users.find((u) => u.file?.name === name && u.file.duration > 0)?.file?.duration ||
      0;
    out.push([{ name, size: file.size, duration, quickHash }, file]);
  }
  return out;
}

/** The room's relay entry for a playlist entry: the same name exactly, readable, and the size a peer reported, if any. */
export function findRelayFile(room: RoomState, fileName: string): RelayFile | null {
  if (!relayActive(room.yarmiplay)) return null;
  const name = Filenames.baseName(fileName);
  const reported = room.users.find((u) => u.file && Filenames.same(u.file.name, name) && u.file.size > 0)?.file?.size;
  return room.yarmiplay.relayFiles.find((f) => f.name === name && isReadable(f) && (!reported || f.size === reported)) ?? null;
}

/** Opens a relay file for the player: Range requests with the session in a header. */
export const openRelayFile = (session: YarmiplaySession, file: RelayFile) =>
  HttpSource.open(`${session.baseUrl}/yarmiplay/files/${encodeURIComponent(file.id)}?mode=stream`, {
    name: file.name,
    headers: auth(session),
  });

/** A link that downloads the whole relay file (the token in the query, as a plain link can't send headers). */
export const downloadLink = (session: YarmiplaySession, file: RelayFile) =>
  `${session.baseUrl}/yarmiplay/files/${encodeURIComponent(file.id)}?mode=download&t=${encodeURIComponent(session.token)}`;

/**
 * Keeps this device's offer in step with the room and serves the server's upload requests. [sharing] off offers
 * nothing (and withdraws what was offered), so requests find no file and are refused.
 */
export class RelaySharing {
  private readonly hashes = new Map<string, string>();
  private offered = new Map<string, LocalFile>();
  private readonly uploads = new Map<string, AbortController>();
  private timer: ReturnType<typeof setTimeout> | undefined;
  private generation = 0;

  constructor(
    private readonly client: () => SyncplayClient | null,
    private readonly library: LocalLibrary,
    private readonly sharing: () => boolean,
    private readonly reported: () => FileInfo | null,
  ) {}

  /** Offers again shortly: the room, the playlist, the files or the session changed (repeats are dropped later). */
  refresh() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.offer(), 300);
  }

  private async offer() {
    const client = this.client();
    if (!client) return;
    const generation = ++this.generation;
    const room = client.state;
    if (!relayActive(room.yarmiplay) || !this.sharing()) {
      this.offered = new Map();
      if (relayActive(room.yarmiplay)) client.offerFiles([]);
      return;
    }
    const list = await offersFor(room.playlist, this.library.files, room.users, this.reported(), (f) => this.hashOf(f));
    if (generation !== this.generation || client !== this.client()) return;
    this.offered = new Map(list.map(([offer, file]) => [`${offer.size}:${offer.quickHash}`, file]));
    client.offerFiles(list.map(([offer]) => offer));
  }

  /** The file's quickHash, or null when it can't be read; cached until the file's size or date changes. */
  private async hashOf(file: LocalFile): Promise<string | null> {
    const key = `${file.folder}/${file.directory}/${file.name}|${file.size}|${file.modified}`;
    const known = this.hashes.get(key);
    if (known) return known;
    try {
      const hash = await quickHash(await file.open());
      this.hashes.set(key, hash);
      return hash;
    } catch {
      return null;
    }
  }

  /** The server wants bytes `[offset, offset + length)` of one of our offered files. */
  async upload(request: UploadRequest, session: YarmiplaySession | null) {
    this.uploads.get(request.id)?.abort();
    const controller = new AbortController();
    this.uploads.set(request.id, controller);
    const fail = (error: string) => this.client()?.reportUploadFailed(request.id, error);
    try {
      const entry = this.offered.get(`${request.size}:${request.quickHash}`);
      if (!entry) return fail("not offered");
      let file: File;
      try {
        file = await entry.open();
      } catch {
        return fail("file not found");
      }
      if (file.size !== request.size) return fail("file changed");
      if (request.offset < 0 || request.length <= 0 || request.offset + request.length > request.size) return fail("bad range");
      if (!session) return;
      let body: ArrayBuffer;
      try {
        body = await file.slice(request.offset, request.offset + request.length).arrayBuffer();
      } catch {
        return fail("read error");
      }
      const response = await fetch(`${session.baseUrl}/yarmiplay/upload/${encodeURIComponent(request.id)}`, {
        method: "PUT",
        headers: { ...auth(session), "Content-Type": "application/octet-stream" },
        body,
        signal: controller.signal,
      });
      // 204/200 done; 404 and 409 mean the server took the request back or gave it to someone else.
      if (![200, 204, 404, 409].includes(response.status)) console.warn(`Relay upload answered ${response.status}`);
    } catch {
      // Aborted, or the network went: the server asks someone else.
    } finally {
      if (this.uploads.get(request.id) === controller) this.uploads.delete(request.id);
    }
  }

  cancel(id: string) {
    this.uploads.get(id)?.abort();
    this.uploads.delete(id);
  }

  cancelAll() {
    for (const c of this.uploads.values()) c.abort();
    this.uploads.clear();
  }
}
