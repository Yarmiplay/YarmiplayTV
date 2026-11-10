/** Title, artist and cover image from the front of an audio file. A miss leaves the fields empty. */
export interface EmbeddedTags {
  title: string | null;
  artist: string | null;
  picture: Uint8Array | null;
  pictureType: string | null;
}

const EMPTY: EmbeddedTags = { title: null, artist: null, picture: null, pictureType: null };

export function readEmbeddedTags(bytes: Uint8Array): EmbeddedTags {
  if (bytes.length >= 3 && bytes[0] === 0x49 && bytes[1] === 0x44 && bytes[2] === 0x33) return readId3(bytes);
  if (text(bytes, 0, 4) === "fLaC") return readFlac(bytes);
  if (text(bytes, 0, 4) === "OggS") {
    const ogg = readOgg(bytes);
    if (ogg.picture || ogg.title || ogg.artist) return ogg;
  }
  return tagged(null, null, readMp4Cover(bytes));
}

function tagged(title: string | null, artist: string | null, picture: Uint8Array | null): EmbeddedTags {
  return { title, artist, picture, pictureType: picture ? mime(picture) : null };
}

function readId3(bytes: Uint8Array): EmbeddedTags {
  if (bytes.length < 10) return EMPTY;
  const version = bytes[3];
  let offset = 10;
  const end = Math.min(bytes.length, 10 + synchsafe(bytes, 6));
  if (version >= 3 && bytes[5] & 0x40 && offset + 4 <= end) {
    const ext = version >= 4 ? synchsafe(bytes, offset) : u32(bytes, offset);
    offset += Math.max(4, ext);
  }
  let title: string | null = null;
  let artist: string | null = null;
  let picture: Uint8Array | null = null;
  const v2 = version <= 2;
  const idLen = v2 ? 3 : 4;
  const sizeLen = v2 ? 3 : 4;
  while (offset + idLen + sizeLen + (v2 ? 0 : 2) <= end) {
    const id = text(bytes, offset, idLen);
    if (!id || id.charCodeAt(0) === 0 || ![...id].every((c) => (c >= "A" && c <= "Z") || (c >= "0" && c <= "9"))) break;
    offset += idLen;
    const size = v2 ? u24(bytes, offset) : version >= 4 ? synchsafe(bytes, offset) : u32(bytes, offset);
    offset += sizeLen;
    if (!v2) offset += 2;
    if (size <= 0 || offset + size > end) break;
    const frame = bytes.subarray(offset, offset + size);
    offset += size;
    if ((id === "TIT2" || id === "TT2") && !title) title = textFrame(frame);
    if ((id === "TPE1" || id === "TP1") && !artist) artist = textFrame(frame);
    if ((id === "APIC" || id === "PIC") && !picture) picture = id === "PIC" ? picV2(frame) : apic(frame);
  }
  return tagged(title, artist, picture);
}

function textFrame(frame: Uint8Array): string | null {
  if (frame.length === 0) return null;
  const value = decodeText(frame[0], frame.subarray(1)).trim();
  return value || null;
}

function apic(frame: Uint8Array): Uint8Array | null {
  if (frame.length < 4) return null;
  let i = 1;
  while (i < frame.length && frame[i] !== 0) i++;
  i += 2;
  if (i >= frame.length) return null;
  i = skipTerminated(frame, i, frame[0]);
  if (i < 0 || i >= frame.length) return null;
  return imageOrNull(frame.subarray(i));
}

function picV2(frame: Uint8Array): Uint8Array | null {
  if (frame.length < 6) return null;
  const i = skipTerminated(frame, 5, frame[0]);
  if (i < 0 || i >= frame.length) return null;
  return imageOrNull(frame.subarray(i));
}

function readFlac(bytes: Uint8Array): EmbeddedTags {
  let offset = 4;
  let title: string | null = null;
  let artist: string | null = null;
  let picture: Uint8Array | null = null;
  for (let guard = 0; offset + 4 <= bytes.length && guard < 64; guard++) {
    const header = bytes[offset];
    const last = (header & 0x80) !== 0;
    const type = header & 0x7f;
    const size = u24(bytes, offset + 1);
    offset += 4;
    if (size < 0 || offset + size > bytes.length) break;
    const block = bytes.subarray(offset, offset + size);
    offset += size;
    if (type === 4) {
      const tags = vorbisComments(block);
      title = title ?? tags.title;
      artist = artist ?? tags.artist;
    } else if (type === 6) {
      picture = picture ?? pictureBlock(block);
    }
    if (last) break;
  }
  return tagged(title, artist, picture);
}

function readOgg(bytes: Uint8Array): EmbeddedTags {
  const ascii = latin1(bytes);
  const title = commentValue(ascii, "TITLE=") ?? commentValue(ascii, "title=");
  const artist = commentValue(ascii, "ARTIST=") ?? commentValue(ascii, "artist=");
  const marker = "METADATA_BLOCK_PICTURE=";
  const at = ascii.indexOf(marker);
  if (at < 0) return tagged(title, artist, null);
  const b64 = ascii.slice(at + marker.length).match(/^[A-Za-z0-9+/=]+/)?.[0] ?? "";
  const decoded = decodeBase64(b64);
  return tagged(title, artist, decoded ? pictureBlock(decoded) : null);
}

function commentValue(ascii: string, key: string): string | null {
  const at = ascii.indexOf(key);
  if (at < 0) return null;
  const value = ascii.slice(at + key.length).split("\u0000", 1)[0].split("\n", 1)[0].trim();
  return value || null;
}

function vorbisComments(block: Uint8Array): EmbeddedTags {
  if (block.length < 8) return EMPTY;
  let offset = 4 + u32le(block, 0);
  if (offset + 4 > block.length) return EMPTY;
  const count = u32le(block, offset);
  offset += 4;
  let title: string | null = null;
  let artist: string | null = null;
  for (let n = 0; n < count && n < 64 && offset + 4 <= block.length; n++) {
    const len = u32le(block, offset);
    offset += 4;
    if (len < 0 || offset + len > block.length) break;
    const comment = new TextDecoder().decode(block.subarray(offset, offset + len));
    offset += len;
    const eq = comment.indexOf("=");
    if (eq <= 0) continue;
    const key = comment.slice(0, eq).toLowerCase();
    const value = comment.slice(eq + 1).trim();
    if (!value) continue;
    if (key === "title" && !title) title = value;
    if ((key === "artist" || key === "albumartist") && !artist) artist = value;
  }
  return tagged(title, artist, null);
}

function pictureBlock(block: Uint8Array): Uint8Array | null {
  if (block.length < 32) return null;
  let offset = 4;
  const mimeLen = u32(block, offset);
  offset += 4 + mimeLen;
  if (mimeLen < 0 || offset + 4 > block.length) return null;
  const descLen = u32(block, offset);
  offset += 4 + descLen + 16;
  if (descLen < 0 || offset + 4 > block.length) return null;
  const dataLen = u32(block, offset);
  offset += 4;
  if (dataLen < 32 || offset + dataLen > block.length) return null;
  return imageOrNull(block.subarray(offset, offset + dataLen));
}

function readMp4Cover(bytes: Uint8Array): Uint8Array | null {
  const covr = [0x63, 0x6f, 0x76, 0x72];
  let from = 0;
  while (from < bytes.length - 16) {
    const at = indexOf(bytes, covr, from);
    if (at < 0) return null;
    const dataAt = indexOf(bytes, [0x64, 0x61, 0x74, 0x61], at);
    if (dataAt < 0) return null;
    if (dataAt < 4 || dataAt - at > 64) {
      from = at + 4;
      continue;
    }
    const atomStart = dataAt - 4;
    const atomSize = u32(bytes, atomStart);
    if (atomSize < 16 || atomStart + atomSize > bytes.length) {
      from = dataAt + 4;
      continue;
    }
    const end = atomStart + atomSize;
    for (let payload = dataAt + 4; payload + 32 <= end && payload <= dataAt + 24; payload++) {
      const image = imageOrNull(bytes.subarray(payload, end));
      if (image) return image;
    }
    from = dataAt + 4;
  }
  return null;
}

function imageOrNull(data: Uint8Array): Uint8Array | null {
  if (data.length < 32 || data.length > 2_000_000) return null;
  const jpeg = data[0] === 0xff && data[1] === 0xd8 && data[2] === 0xff;
  const png = data[0] === 0x89 && data[1] === 0x50 && data[2] === 0x4e && data[3] === 0x47;
  const webp = data.length > 12 && text(data, 0, 4) === "RIFF" && text(data, 8, 4) === "WEBP";
  return jpeg || png || webp ? data : null;
}

function mime(data: Uint8Array): string {
  if (data[0] === 0x89) return "image/png";
  if (data.length > 12 && text(data, 0, 4) === "RIFF") return "image/webp";
  return "image/jpeg";
}

function skipTerminated(frame: Uint8Array, start: number, encoding: number): number {
  let i = start;
  if (encoding === 1 || encoding === 2) {
    while (i + 1 < frame.length) {
      if (frame[i] === 0 && frame[i + 1] === 0) return i + 2;
      i += 2;
    }
    return -1;
  }
  while (i < frame.length) {
    if (frame[i] === 0) return i + 1;
    i++;
  }
  return -1;
}

function decodeText(encoding: number, data: Uint8Array): string {
  const kind = encoding === 0 ? "iso-8859-1" : encoding === 1 || encoding === 2 ? "utf-16" : "utf-8";
  return new TextDecoder(kind).decode(data).replaceAll("\u0000", "").trim();
}

function indexOf(haystack: Uint8Array, needle: number[], from: number): number {
  for (let i = from; i + needle.length <= haystack.length; i++) {
    let ok = true;
    for (let j = 0; j < needle.length; j++) {
      if (haystack[i + j] !== needle[j]) {
        ok = false;
        break;
      }
    }
    if (ok) return i;
  }
  return -1;
}

function synchsafe(bytes: Uint8Array, offset: number): number {
  if (offset + 4 > bytes.length) return 0;
  return ((bytes[offset] & 0x7f) << 21) | ((bytes[offset + 1] & 0x7f) << 14) | ((bytes[offset + 2] & 0x7f) << 7) | (bytes[offset + 3] & 0x7f);
}

function u32(bytes: Uint8Array, offset: number): number {
  if (offset + 4 > bytes.length) return -1;
  return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0;
}

function u32le(bytes: Uint8Array, offset: number): number {
  if (offset + 4 > bytes.length) return -1;
  return (bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24)) >>> 0;
}

function u24(bytes: Uint8Array, offset: number): number {
  if (offset + 3 > bytes.length) return -1;
  return (bytes[offset] << 16) | (bytes[offset + 1] << 8) | bytes[offset + 2];
}

function text(bytes: Uint8Array, offset: number, length: number): string {
  return latin1(bytes.subarray(offset, offset + length));
}

function latin1(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
  return out;
}

function decodeBase64(value: string): Uint8Array | null {
  if (!value) return null;
  try {
    const binary = atob(value);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}
