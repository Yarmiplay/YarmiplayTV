/** One piece of a chat message. [href] is set only for an http(s) link. */
export type ChatPart = { text: string; href?: string };

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

/** Splits [text] into plain runs and links. Never returns HTML. */
export function chatParts(text: string): ChatPart[] {
  const finder = /(?<![A-Za-z0-9@/])(?:https?:\/\/|www\.)\S+/gi;
  const parts: ChatPart[] = [];
  let cursor = 0;
  for (const match of text.matchAll(finder)) {
    const raw = match[0];
    const start = match.index ?? 0;
    const trimmed = trimTrailing(raw);
    const href = openUrl(trimmed);
    if (!href) continue;
    if (start > cursor) parts.push({ text: text.slice(cursor, start) });
    parts.push({ text: text.slice(start, start + trimmed.length), href });
    cursor = start + trimmed.length;
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor) });
  if (parts.length === 0) parts.push({ text });
  return parts;
}
