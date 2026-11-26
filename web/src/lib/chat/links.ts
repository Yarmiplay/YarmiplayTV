/** One piece of a chat message. [href] is set for an http(s) link; [timestampSeconds] for a clickable timestamp. */
export type ChatPart = { text: string; href?: string; timestampSeconds?: number };

const TRAILING = new Set([".", ",", ";", ":", "!", "?", ")", "]", "}", "'", '"', "*"]);

function trimTrailing(raw: string): string {
  let end = raw.length;
  while (end > 0 && TRAILING.has(raw[end - 1]!)) {
    if (raw[end - 1] === ")") {
      const slice = raw.slice(0, end);
      const open = (slice.match(/\(/g) ?? []).length;
      const close = (slice.match(/\)/g) ?? []).length;
      if (open >= close) break;
    }
    end--;
  }
  return raw.slice(0, end);
}

function openUrl(trimmed: string): string | null {
  const lower = trimmed.toLowerCase();
  let schemeLen = 0;
  if (lower.startsWith("https://")) schemeLen = 8;
  else if (lower.startsWith("http://")) schemeLen = 7;
  else if (lower.startsWith("www.")) schemeLen = 4;
  else return null;
  const rest = trimmed.slice(schemeLen);
  if (!rest || /^[./]+$/.test(rest)) return null;
  return lower.startsWith("www.") ? `https://${trimmed}` : trimmed;
}

/** Formats [seconds] as `M:SS` or `H:MM:SS` for chat messages (e.g. `14:22` or `1:05:10`). */
export function formatChatTimestamp(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

interface Span {
  start: number;
  end: number;
  href?: string;
  timestampSeconds?: number;
}

/** Splits [text] into plain runs, http(s) links, and clickable playback timestamps. Never returns HTML. */
export function chatParts(text: string): ChatPart[] {
  const urlFinder = /(?<![A-Za-z0-9@/])(?:https?:\/\/|www\.)\S+/gi;
  const tsFinder = /(?<![A-Za-z0-9:/._-])@?(?:(\d{1,2}):)?(\d{1,3}):([0-5]\d)(?![A-Za-z0-9:/._-])/g;
  const urls: Span[] = [];
  for (const match of text.matchAll(urlFinder)) {
    const raw = match[0];
    const start = match.index ?? 0;
    const trimmed = trimTrailing(raw);
    const href = openUrl(trimmed);
    if (!href) continue;
    urls.push({ start, end: start + trimmed.length, href });
  }
  const spans: Span[] = [...urls];
  if (text.includes(":")) {
    for (const match of text.matchAll(tsFinder)) {
      const start = match.index ?? 0;
      const end = start + match[0].length;
      if (urls.some((u) => start < u.end && end > u.start)) continue;
      const hours = match[1] !== undefined ? Number(match[1]) : null;
      const minutes = Number(match[2]);
      const seconds = Number(match[3]);
      if (hours !== null && (hours < 0 || hours > 99 || minutes < 0 || minutes > 59)) continue;
      if (hours === null && (minutes < 0 || minutes > 599)) continue;
      spans.push({ start, end, timestampSeconds: (hours ?? 0) * 3600 + minutes * 60 + seconds });
    }
  }
  spans.sort((a, b) => a.start - b.start);
  const parts: ChatPart[] = [];
  let cursor = 0;
  for (const span of spans) {
    if (span.start < cursor) continue;
    if (span.start > cursor) parts.push({ text: text.slice(cursor, span.start) });
    if (span.href) parts.push({ text: text.slice(span.start, span.end), href: span.href });
    else if (span.timestampSeconds !== undefined) parts.push({ text: text.slice(span.start, span.end), timestampSeconds: span.timestampSeconds });
    cursor = span.end;
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor) });
  if (parts.length === 0) parts.push({ text });
  return parts;
}
