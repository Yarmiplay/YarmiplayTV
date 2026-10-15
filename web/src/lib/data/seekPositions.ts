const PREFIX = "ytv:seek:";

/**
 * Persists the latest seeking/playback position for files locally on this client,
 * so when a file loads (standalone or in a room), playback can resume where it last was seeked.
 */
export function getSeekPosition(fileName: string | null | undefined): number {
  if (!fileName || typeof localStorage === "undefined") return 0;
  try {
    const raw = localStorage.getItem(PREFIX + fileName.toLowerCase());
    if (!raw) return 0;
    const pos = parseFloat(raw);
    return isFinite(pos) && pos > 0 ? pos : 0;
  } catch {
    return 0;
  }
}

export function saveSeekPosition(fileName: string | null | undefined, position: number): void {
  if (!fileName || !isFinite(position) || position <= 0 || typeof localStorage === "undefined") return;
  try {
    localStorage.setItem(PREFIX + fileName.toLowerCase(), position.toString());
  } catch {
    // ignore quota/private mode errors
  }
}

export function clearSeekPosition(fileName: string | null | undefined): void {
  if (!fileName || typeof localStorage === "undefined") return;
  try {
    localStorage.removeItem(PREFIX + fileName.toLowerCase());
  } catch {
    // ignore
  }
}
