/** Online subtitle search for the Web app (uses YarmiplayServerTV's `/yarmiplay/subs/*` passthrough). */

export interface SubtitleSearchQuery {
  query: string;
  language: string;
  season?: number | null;
  episode?: number | null;
  movieHash?: string | null;
  fileName?: string | null;
}

export interface SubtitleSearchResult {
  id: string;
  provider: "opensubtitles" | "subdl";
  releaseName: string;
  language: string;
  format: string;
  downloads: number;
  hearingImpaired: boolean;
  exactHashMatch: boolean;
  fileId?: number;
  downloadPath?: string;
}

export interface DownloadedSubtitle {
  fileName: string;
  language: string;
  text: string;
}

export const SUBTITLE_LANGUAGES: readonly { code: string; label: string }[] = [
  { code: "en", label: "English" },
  { code: "es", label: "Spanish" },
  { code: "fr", label: "French" },
  { code: "de", label: "German" },
  { code: "pt", label: "Portuguese" },
  { code: "pt-br", label: "Portuguese (Brazil)" },
  { code: "it", label: "Italian" },
  { code: "ja", label: "Japanese" },
  { code: "ko", label: "Korean" },
  { code: "zh-cn", label: "Chinese (Simplified)" },
  { code: "zh-tw", label: "Chinese (Traditional)" },
  { code: "ru", label: "Russian" },
  { code: "ar", label: "Arabic" },
  { code: "he", label: "Hebrew" },
  { code: "nl", label: "Dutch" },
  { code: "pl", label: "Polish" },
  { code: "tr", label: "Turkish" },
  { code: "sv", label: "Swedish" },
  { code: "ro", label: "Romanian" },
  { code: "vi", label: "Vietnamese" },
  { code: "id", label: "Indonesian" },
  { code: "all", label: "Any language" },
];

const CHUNK = 64 * 1024;
const MASK64 = (1n << 64n) - 1n;

/** 64-bit OpenSubtitles moviehash for a local browser File. */
export async function computeOpenSubtitlesHash(file: Blob): Promise<string | null> {
  if (file.size < CHUNK) return null;
  const first = new DataView(await file.slice(0, CHUNK).arrayBuffer());
  const last = new DataView(await file.slice(file.size - CHUNK, file.size).arrayBuffer());
  let hash = BigInt(file.size);
  for (let i = 0; i < CHUNK; i += 8) {
    hash = (hash + first.getBigUint64(i, true)) & MASK64;
    hash = (hash + last.getBigUint64(i, true)) & MASK64;
  }
  return hash.toString(16).padStart(16, "0");
}

function subdlLang(code: string): string {
  const lower = code.toLowerCase();
  if (lower === "pt-br") return "BR_PT";
  if (lower === "zh-cn" || lower === "zh-tw") return "ZH";
  return lower.split("-")[0].toUpperCase();
}

export class WebSubtitleSearchClient {
  constructor(
    private readonly openSubtitlesApiKey: string,
    private readonly subdlApiKey: string,
    private readonly serverBaseUrl: () => string | null,
    private readonly openSubtitlesToken: () => string | null = () => null,
  ) {}

  get configured(): boolean {
    return Boolean(this.openSubtitlesApiKey || this.subdlApiKey);
  }

  async search(q: SubtitleSearchQuery): Promise<SubtitleSearchResult[]> {
    const base = this.serverBaseUrl()?.replace(/\/+$/, "");
    if (!base) throw new Error("Join a YarmiplayServerTV room to search online subtitles in the browser");
    if (!this.configured) throw new Error("Online subtitle search keys are not configured in this build");
    const tasks: Promise<SubtitleSearchResult[]>[] = [];
    if (this.openSubtitlesApiKey) tasks.push(this.searchOpenSubtitles(base, q));
    if (this.subdlApiKey) tasks.push(this.searchSubdl(base, q));
    const settled = await Promise.allSettled(tasks);
    const out: SubtitleSearchResult[] = [];
    let firstError: Error | null = null;
    for (const s of settled) {
      if (s.status === "fulfilled") out.push(...s.value);
      else if (!firstError) firstError = s.reason instanceof Error ? s.reason : new Error(String(s.reason));
    }
    if (!out.length && firstError) throw firstError;
    return out.sort((a, b) => Number(b.exactHashMatch) - Number(a.exactHashMatch) || b.downloads - a.downloads);
  }

  async download(result: SubtitleSearchResult): Promise<DownloadedSubtitle> {
    const base = this.serverBaseUrl()?.replace(/\/+$/, "");
    if (!base) throw new Error("Join a YarmiplayServerTV room to download online subtitles");
    if (result.provider === "opensubtitles") return this.downloadOpenSubtitles(base, result);
    return this.downloadSubdl(base, result);
  }

  private async searchOpenSubtitles(base: string, q: SubtitleSearchQuery): Promise<SubtitleSearchResult[]> {
    const params = new URLSearchParams();
    if (q.movieHash && q.movieHash.length === 16) params.set("moviehash", q.movieHash);
    if (q.query.trim()) params.set("query", q.query.trim());
    const lang = q.language.trim().toLowerCase();
    if (lang && lang !== "all") params.set("languages", lang);
    if (q.season != null) params.set("season_number", String(q.season));
    if (q.episode != null) params.set("episode_number", String(q.episode));
    const headers: Record<string, string> = { "Api-Key": this.openSubtitlesApiKey, Accept: "application/json" };
    const tok = this.openSubtitlesToken();
    if (tok) headers.Authorization = `Bearer ${tok}`;
    const res = await fetch(`${base}/yarmiplay/subs/opensubtitles/subtitles?${params}`, { headers });
    if (!res.ok) throw new Error(`OpenSubtitles answered ${res.status}`);
    const body = (await res.json()) as {
      data?: {
        id?: string;
        attributes?: {
          language?: string;
          release?: string;
          download_count?: number;
          hearing_impaired?: boolean;
          moviehash_match?: boolean;
          files?: { file_id: number; file_name?: string }[];
        };
      }[];
    };
    const out: SubtitleSearchResult[] = [];
    for (const item of body.data ?? []) {
      const attrs = item.attributes;
      const file = attrs?.files?.[0];
      if (!attrs || !file) continue;
      const name = attrs.release?.trim() || file.file_name?.trim() || `Subtitle ${item.id ?? ""}`;
      const ext = file.file_name?.split(".").pop()?.toLowerCase() || "srt";
      out.push({
        id: `os:${file.file_id}`,
        provider: "opensubtitles",
        releaseName: name,
        language: attrs.language || q.language,
        format: ext,
        downloads: attrs.download_count ?? 0,
        hearingImpaired: Boolean(attrs.hearing_impaired),
        exactHashMatch: Boolean(attrs.moviehash_match),
        fileId: file.file_id,
      });
    }
    return out;
  }

  private async downloadOpenSubtitles(base: string, result: SubtitleSearchResult): Promise<DownloadedSubtitle> {
    if (!result.fileId) throw new Error("Missing OpenSubtitles file id");
    const headers: Record<string, string> = {
      "Api-Key": this.openSubtitlesApiKey,
      "Content-Type": "application/json",
      Accept: "application/json",
    };
    const tok = this.openSubtitlesToken();
    if (tok) headers.Authorization = `Bearer ${tok}`;
    const res = await fetch(`${base}/yarmiplay/subs/opensubtitles/download`, {
      method: "POST",
      headers,
      body: JSON.stringify({ file_id: result.fileId }),
    });
    if (!res.ok) {
      throw new Error(res.status === 406 ? "OpenSubtitles daily download limit reached" : `OpenSubtitles download failed (${res.status})`);
    }
    const data = (await res.json()) as { link?: string; file_name?: string; message?: string };
    if (!data.link) throw new Error(data.message || "OpenSubtitles did not return a download link");
    const fileRes = await fetch(`${base}/yarmiplay/subs/opensubtitles-file?url=${encodeURIComponent(data.link)}`);
    if (!fileRes.ok) throw new Error(`Subtitle file download failed (${fileRes.status})`);
    const bytes = new Uint8Array(await fileRes.arrayBuffer());
    return extractSubtitleBytes(bytes, data.file_name || `${result.releaseName}.${result.format}`, result.language);
  }

  private async searchSubdl(base: string, q: SubtitleSearchQuery): Promise<SubtitleSearchResult[]> {
    const params = new URLSearchParams({ api_key: this.subdlApiKey, subs_per_page: "20" });
    if (q.query.trim()) params.set("film_name", q.query.trim());
    if (q.fileName?.trim()) params.set("file_name", q.fileName.trim());
    const lang = q.language.trim().toLowerCase();
    if (lang && lang !== "all") params.set("languages", subdlLang(lang));
    if (q.season != null || q.episode != null) {
      params.set("type", "tv");
      if (q.season != null) params.set("season_number", String(q.season));
      if (q.episode != null) params.set("episode_number", String(q.episode));
    }
    const res = await fetch(`${base}/yarmiplay/subs/subdl/api/subtitles?${params}`);
    if (!res.ok) throw new Error(`Subdl answered ${res.status}`);
    const body = (await res.json()) as {
      subtitles?: {
        name?: string;
        release_name?: string;
        url?: string;
        lang?: string;
        hi?: boolean;
        downloads_count?: number;
      }[];
    };
    const out: SubtitleSearchResult[] = [];
    for (const sub of body.subtitles ?? []) {
      if (!sub.url) continue;
      out.push({
        id: `sd:${sub.url}`,
        provider: "subdl",
        releaseName: sub.release_name?.trim() || sub.name?.trim() || "Subdl subtitle",
        language: sub.lang?.toLowerCase() || q.language,
        format: "srt",
        downloads: sub.downloads_count ?? 0,
        hearingImpaired: Boolean(sub.hi),
        exactHashMatch: false,
        downloadPath: sub.url,
      });
    }
    return out;
  }

  private async downloadSubdl(base: string, result: SubtitleSearchResult): Promise<DownloadedSubtitle> {
    if (!result.downloadPath) throw new Error("Missing Subdl download path");
    const rel = result.downloadPath.replace(/^\/+/, "");
    const res = await fetch(`${base}/yarmiplay/subs/subdl/dl/${rel}`);
    if (!res.ok) throw new Error(`Subdl download failed (${res.status})`);
    const bytes = new Uint8Array(await res.arrayBuffer());
    return extractSubtitleBytes(bytes, `${result.releaseName}.srt`, result.language);
  }
}

export async function extractSubtitleBytes(bytes: Uint8Array, fallbackName: string, language: string): Promise<DownloadedSubtitle> {
  const decoder = new TextDecoder("utf-8");
  if (bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b) {
    const entries = await unzipEntries(bytes);
    let bestName: string | null = null;
    let bestData: Uint8Array | null = null;
    for (const { name, data } of entries) {
      const base = name.split(/[/\\]/).pop() ?? name;
      const ext = base.split(".").pop()?.toLowerCase() ?? "";
      if (["ass", "ssa", "srt", "vtt"].includes(ext)) {
        if (!bestData || ext === "ass" || ext === "srt") {
          bestName = base;
          bestData = data;
          if (ext === "ass") break;
        }
      }
    }
    if (bestName && bestData) {
      return { fileName: bestName, language, text: decoder.decode(bestData) };
    }
    throw new Error("No subtitle file found inside the archive");
  }
  return { fileName: fallbackName, language, text: decoder.decode(bytes) };
}

/** Zero-dependency ZIP reader using the platform's DecompressionStream("deflate-raw"). */
async function unzipEntries(zip: Uint8Array): Promise<{ name: string; data: Uint8Array }[]> {
  const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  // Find End of Central Directory record (0x06054b50).
  let eocd = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 65557); i--) {
    if (view.getUint32(i, true) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) return [];
  const cdCount = view.getUint16(eocd + 10, true);
  let pos = view.getUint32(eocd + 16, true);
  const nameDecoder = new TextDecoder("utf-8");
  const out: { name: string; data: Uint8Array }[] = [];
  for (let i = 0; i < cdCount && pos + 46 <= zip.length; i++) {
    if (view.getUint32(pos, true) !== 0x02014b50) break;
    const method = view.getUint16(pos + 10, true);
    const compSize = view.getUint32(pos + 20, true);
    const nameLen = view.getUint16(pos + 28, true);
    const extraLen = view.getUint16(pos + 30, true);
    const commentLen = view.getUint16(pos + 32, true);
    const localOffset = view.getUint32(pos + 42, true);
    const name = nameDecoder.decode(zip.subarray(pos + 46, pos + 46 + nameLen));
    pos += 46 + nameLen + extraLen + commentLen;
    if (name.endsWith("/") || localOffset + 30 > zip.length) continue;
    const lNameLen = view.getUint16(localOffset + 26, true);
    const lExtraLen = view.getUint16(localOffset + 28, true);
    const dataStart = localOffset + 30 + lNameLen + lExtraLen;
    const raw = zip.subarray(dataStart, dataStart + compSize);
    if (method === 0) {
      out.push({ name, data: raw });
    } else if (method === 8 && typeof DecompressionStream !== "undefined") {
      const ds = new DecompressionStream("deflate-raw");
      const writer = ds.writable.getWriter();
      void writer.write(new Uint8Array(raw)).then(() => writer.close());
      const buf = await new Response(ds.readable).arrayBuffer();
      out.push({ name, data: new Uint8Array(buf) });
    }
  }
  return out;
}
