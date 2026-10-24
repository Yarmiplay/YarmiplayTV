/** `yv1` packet seal/open (VOICE_PROTOCOL §6.1) for the browser WebSocket path. */

export const YV1 = {
  VERSION: 1,
  TYPE_AUDIO: 1,
  TYPE_KEEPALIVE: 2,
  TYPE_BYE: 3,
  HEADER_LEN: 40,
  TAG_LEN: 16,
  MIN_PACKET: 56,
  MAX_PACKET: 1200,
} as const;

export type OpenedPacket = {
  type: number;
  sender: bigint;
  sequence: number;
  timestamp: number;
  plain: Uint8Array;
};

function writeU64BE(view: DataView, offset: number, value: bigint) {
  view.setBigUint64(offset, value, false);
}

function readU64BE(view: DataView, offset: number): bigint {
  return view.getBigUint64(offset, false);
}

export function parseTokenHex(hex: string): bigint | null {
  if (!/^[0-9a-f]{16}$/.test(hex)) return null;
  return BigInt(`0x${hex}`);
}

export function decodeKeyBase64(b64: string): Uint8Array | null {
  try {
    const bin = atob(b64);
    if (bin.length !== 32) return null;
    const out = new Uint8Array(32);
    for (let i = 0; i < 32; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

function asBufferSource(bytes: Uint8Array): Uint8Array<ArrayBuffer> {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy;
}

async function importKey(raw: Uint8Array): Promise<CryptoKey> {
  return crypto.subtle.importKey("raw", asBufferSource(raw), { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

function buildHeader(
  type: number,
  token: bigint,
  sender: bigint,
  sequence: number,
  timestamp: number,
  nonce: Uint8Array,
): Uint8Array {
  const h = new Uint8Array(YV1.HEADER_LEN);
  h[0] = 0x59; // Y
  h[1] = 0x56; // V
  h[2] = YV1.VERSION;
  h[3] = type;
  const view = new DataView(h.buffer);
  writeU64BE(view, 4, token);
  writeU64BE(view, 12, sender);
  view.setUint32(20, sequence >>> 0, false);
  view.setUint32(24, timestamp >>> 0, false);
  h.set(nonce, 28);
  return h;
}

export async function seal(
  keyRaw: Uint8Array,
  type: number,
  token: bigint,
  sender: bigint,
  sequence: number,
  timestamp: number,
  plain: Uint8Array,
): Promise<Uint8Array> {
  const key = await importKey(keyRaw);
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const header = buildHeader(type, token, sender, sequence, timestamp, nonce);
  const ct = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: asBufferSource(nonce), additionalData: asBufferSource(header), tagLength: 128 },
      key,
      asBufferSource(plain),
    ),
  );
  const out = new Uint8Array(YV1.HEADER_LEN + ct.length);
  out.set(header, 0);
  out.set(ct, YV1.HEADER_LEN);
  return out;
}

export async function open(keyRaw: Uint8Array, packet: Uint8Array): Promise<OpenedPacket | null> {
  if (packet.length < YV1.MIN_PACKET || packet.length > YV1.MAX_PACKET) return null;
  if (packet[0] !== 0x59 || packet[1] !== 0x56 || packet[2] !== YV1.VERSION) return null;
  const header = packet.subarray(0, YV1.HEADER_LEN);
  const ct = packet.subarray(YV1.HEADER_LEN);
  const nonce = header.subarray(28, 40);
  try {
    const key = await importKey(keyRaw);
    const plain = new Uint8Array(
      await crypto.subtle.decrypt(
        { name: "AES-GCM", iv: asBufferSource(nonce), additionalData: asBufferSource(header), tagLength: 128 },
        key,
        asBufferSource(ct),
      ),
    );
    const view = new DataView(header.buffer, header.byteOffset, header.byteLength);
    return {
      type: header[3],
      sender: readU64BE(view, 12),
      sequence: view.getUint32(20, false),
      timestamp: view.getUint32(24, false),
      plain,
    };
  } catch {
    return null;
  }
}
