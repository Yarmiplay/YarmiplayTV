import type { DeviceAuth } from "../syncplay/yarmiplay";

/**
 * This browser's device keys for YarmiplayServerTV: one ECDSA P-256 key pair per server, made with WebCrypto and kept
 * in IndexedDB as a non-extractable CryptoKey, so the private key can sign but never be read out (as the apps keep
 * theirs in the Android KeyStore or a private file).
 */

const DB = "yarmiplaytv";
const STORE = "deviceKeys";

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function withStore<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb();
  try {
    return await new Promise<T>((resolve, reject) => {
      const request = run(db.transaction(STORE, mode).objectStore(STORE));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  } finally {
    db.close();
  }
}

/** A DER INTEGER of the unsigned big-endian [bytes]: leading zeros dropped, one added when the top bit is set. */
function derInteger(bytes: Uint8Array): Uint8Array {
  let start = 0;
  while (start < bytes.length - 1 && bytes[start] === 0) start++;
  const body = bytes.subarray(start);
  const pad = body[0] & 0x80 ? 1 : 0;
  const out = new Uint8Array(2 + pad + body.length);
  out[0] = 0x02;
  out[1] = pad + body.length;
  out.set(body, 2 + pad);
  return out;
}

/** WebCrypto's raw r‖s ECDSA signature as the DER SEQUENCE Java and the server expect. */
export function rawToDer(raw: Uint8Array): Uint8Array {
  const half = raw.length / 2;
  const r = derInteger(raw.subarray(0, half));
  const s = derInteger(raw.subarray(half));
  const out = new Uint8Array(2 + r.length + s.length);
  out[0] = 0x30;
  out[1] = r.length + s.length;
  out.set(r, 2);
  out.set(s, 2 + r.length);
  return out;
}

export class BrowserDeviceKeys implements DeviceAuth {
  private readonly cache = new Map<string, Promise<CryptoKeyPair>>();

  constructor(readonly deviceName: string) {}

  private pair(serverId: string): Promise<CryptoKeyPair> {
    let pair = this.cache.get(serverId);
    if (!pair) {
      pair = (async () => {
        const stored = await withStore<CryptoKeyPair | undefined>("readonly", (s) => s.get(serverId));
        if (stored) return stored;
        const made = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
        await withStore("readwrite", (s) => s.put(made, serverId));
        return made;
      })();
      pair.catch(() => this.cache.delete(serverId));
      this.cache.set(serverId, pair);
    }
    return pair;
  }

  async publicKey(serverId: string): Promise<Uint8Array> {
    const { publicKey } = await this.pair(serverId);
    return new Uint8Array(await crypto.subtle.exportKey("spki", publicKey));
  }

  async sign(serverId: string, message: Uint8Array): Promise<Uint8Array> {
    const { privateKey } = await this.pair(serverId);
    const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, privateKey, message as BufferSource));
    return rawToDer(raw);
  }

  /** Forgets every server's key (signing out of everything). */
  static async clear(): Promise<void> {
    await withStore("readwrite", (s) => s.clear());
  }
}
