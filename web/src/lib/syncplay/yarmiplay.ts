import { sha256Bytes } from "./hash";

/**
 * The YarmiplayServerTV extensions to Syncplay 1.7 (extension protocol 1, Yarmiplay.kt): device access, the file
 * relay, Jellyfin sharing and room settings. The web client only connects to YarmiplayServerTV.
 */
export const Yarmiplay = {
  PROTOCOL: 1,
  SERVER_NAME: "YarmiplayServerTV",
  SIGNED_PREFIX: "YarmiplayServerTV-device-auth-v1",
  MAX_OFFERED_FILES: 200,
  /** The DER header of a P-256 SubjectPublicKeyInfo, before the uncompressed point. */
  P256_SPKI_PREFIX: "3059301306072a8648ce3d020106082a8648ce3d030107034200",
} as const;

export const isValidServerId = (id: string): boolean => /^[0-9a-f]{32}$/.test(id);

/** What a device signs to answer a challenge. */
export const signedMessage = (serverId: string, nonce: string): Uint8Array =>
  new TextEncoder().encode(`${Yarmiplay.SIGNED_PREFIX}\n${serverId}\n${nonce}`);

/** SHA-256 of the SubjectPublicKeyInfo DER: the first 16 hex digits in uppercase, in groups of four. */
export function fingerprint(spki: Uint8Array): string {
  const hex = Array.from(sha256Bytes(spki).subarray(0, 8), (b) => b.toString(16).padStart(2, "0").toUpperCase()).join("");
  return hex.match(/.{4}/g)!.join("-");
}

export type ServerKind = "UNKNOWN" | "SYNCPLAY" | "YARMIPLAY";

/** Where this device stands with a YarmiplayServerTV that admits approved devices. */
export type DeviceState =
  | { kind: "none" }
  | { kind: "pending"; fingerprint: string }
  | { kind: "approved" }
  | { kind: "denied" }
  | { kind: "expired" }
  /** The key isn't approved and this connection didn't ask for access. */
  | { kind: "required" }
  /** The host removed this device while it was in a room. */
  | { kind: "revoked" };

/** States after which the client stops instead of reconnecting. */
export const isRefused = (d: DeviceState): boolean => ["denied", "expired", "required", "revoked"].includes(d.kind);

/** This device's key for one server; the private key never leaves it. */
export interface DeviceAuth {
  /** Shown to the host; the server keeps 60 characters. */
  readonly deviceName: string;
  /** The SubjectPublicKeyInfo DER of the P-256 key for [serverId], created on first use. */
  publicKey(serverId: string): Promise<Uint8Array>;
  /** A DER ECDSA (SHA-256) signature over [message] with [serverId]'s key. */
  sign(serverId: string, message: Uint8Array): Promise<Uint8Array>;
}

export interface Capabilities {
  fileRelay: boolean;
  jellyfin: boolean;
  /** The host shares its Plex too (YarmiplayServerTV 1.7). */
  plex: boolean;
  https: boolean;
  /** The relay takes `mode=prefetch` reads; older servers get `mode=download` for those. */
  prefetch: boolean;
  /** Room-wide settings everyone in the room shares; older servers lack them. */
  roomSettings: boolean;
  /** The web's: the server takes WebSocket connections at /yarmiplay/ws. */
  websocket: boolean;
  /** The web's: the server passes MyAnimeList calls through (MAL refuses browsers' cross-origin requests). */
  mal: boolean;
  /** Room voice chat (YarmiplayServerTV 2.0+). */
  voice: boolean;
}

export const noCapabilities = (): Capabilities => ({
  fileRelay: false,
  jellyfin: false,
  plex: false,
  https: false,
  prefetch: false,
  roomSettings: false,
  websocket: false,
  mal: false,
  voice: false,
});

/** How to join voice when [Capabilities.voice] is true (`yv1` frames on `/yarmiplay/voice`). */
export interface VoiceDescriptor {
  version: number;
  codec: string;
  clockRate: number;
  channels: number;
  ptimeMs: number;
  maxBitrate: number;
  fec: boolean;
  dtx: boolean;
  transport: string;
  udpPort: number;
  tcpPort: number;
  wsPath: string;
  maxParticipants: number;
  fingerprint: string;
}

export interface VoicePeer {
  participantId: string;
  username: string;
  speaking: boolean;
}

export type VoiceClosedReason =
  | "left"
  | "disabled"
  | "vanilla"
  | "full"
  | "room"
  | "replaced"
  | "version"
  | "error"
  | "unknown";

export function parseVoiceClosedReason(value: string | null | undefined): VoiceClosedReason {
  switch (value) {
    case "left":
    case "disabled":
    case "vanilla":
    case "full":
    case "room":
    case "replaced":
    case "version":
    case "error":
      return value;
    case "toggle":
    case "off":
      return "disabled";
    default:
      return "unknown";
  }
}

/** Vanilla mode: the host switched every extension off, and the session token is revoked. */
export const allOff = (c: Capabilities): boolean => !c.fileRelay && !c.jellyfin && !c.https;

/** Settings that are the room's, the same for everyone in it, as the server keeps them. */
export interface RoomSettings {
  /** Skip anime openings and endings automatically (the room jumps, as when anyone seeks). */
  autoSkip: boolean;
  /** Who last changed them; null when nobody has in this room yet. */
  setBy: string | null;
}

/** The host's Jellyfin, as the server shares it. */
export interface SharedJellyfin {
  available: boolean;
  serverId: string;
  serverName: string;
  /** Jellyfin also answers on the Syncplay host and port. */
  proxy: boolean;
  addresses: string[];
}

/** A file another viewer in the room can serve through the server. */
export interface RelayFile {
  /** The file's id in this room, for `/yarmiplay/files/<id>`. */
  id: string;
  name: string;
  size: number;
  duration: number;
  quickHash: string;
  /** Connected viewers offering it; with 0 the entry is only what the server has cached. */
  sources: number;
  cachedBytes: number;
  /** Bytes per second the server currently receives from seeders for this file. */
  rate: number;
}

/** Whether all of the file can be read: someone seeds it, or the server has every byte. */
export const isReadable = (f: RelayFile): boolean => f.sources > 0 || (f.size > 0 && f.cachedBytes >= f.size);

/** A local file this device can serve to the room. */
export interface RelayOffer {
  name: string;
  size: number;
  duration: number;
  quickHash: string;
}

/** The server asks a seeder for bytes `[offset, offset + length)` of the file matching [size] and [quickHash]. */
export interface UploadRequest {
  id: string;
  file: string;
  size: number;
  quickHash: string;
  offset: number;
  length: number;
}

/** The server's certificate name, its LAN addresses (for native clients) and, for browsers, its LAN name. */
export interface ServerTls {
  name: string;
  lan: string[];
  lanName: string | null;
}

/** The extension state of the current connection (no secrets: the token is in [YarmiplaySession]). */
export interface YarmiplayInfo {
  serverKind: ServerKind;
  serverVersion: string | null;
  /** The negotiated extension protocol, 0 without a session. */
  protocol: number;
  /** The server's id from its device challenge (32 hex digits), when it sent one. */
  serverId: string | null;
  /** `open`, `password` or `approved`, from the Hello's marker. */
  access: string | null;
  approvedDevice: boolean;
  device: DeviceState;
  capabilities: Capabilities;
  jellyfin: SharedJellyfin;
  relayFiles: RelayFile[];
  session: boolean;
  tls: ServerTls | null;
  roomSettings: RoomSettings | null;
  /** Present when [capabilities.voice] is true. */
  voice: VoiceDescriptor | null;
}

export const emptyYarmiplayInfo = (): YarmiplayInfo => ({
  serverKind: "UNKNOWN",
  serverVersion: null,
  protocol: 0,
  serverId: null,
  access: null,
  approvedDevice: false,
  device: { kind: "none" },
  capabilities: noCapabilities(),
  jellyfin: { available: false, serverId: "", serverName: "", proxy: false, addresses: [] },
  relayFiles: [],
  session: false,
  tls: null,
  roomSettings: null,
  voice: null,
});

/** The HTTP side of an extension session: base URL on the Syncplay host and port, and the bearer token. */
export interface YarmiplaySession {
  token: string;
  protocol: number;
  baseUrl: string;
}

const MIB = 1 << 20;

/**
 * SHA-256 over the first `min(1 MiB, size)` bytes, the last `min(1 MiB, size)` bytes, and the size as an unsigned
 * 64-bit little-endian integer, in lowercase hex; files under 1 MiB go in twice.
 */
export async function quickHash(file: Blob): Promise<string> {
  const size = file.size;
  if (size <= 0) throw new Error("Empty file");
  const n = Math.min(MIB, size);
  const [head, tail] = await Promise.all([file.slice(0, n).arrayBuffer(), file.slice(size - n, size).arrayBuffer()]);
  const all = new Uint8Array(2 * n + 8);
  all.set(new Uint8Array(head), 0);
  all.set(new Uint8Array(tail), n);
  const view = new DataView(all.buffer);
  view.setUint32(2 * n, size % 0x100000000, true);
  view.setUint32(2 * n + 4, Math.floor(size / 0x100000000), true);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", all));
  return Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
}
