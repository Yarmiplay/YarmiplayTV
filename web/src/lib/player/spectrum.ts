/** Bar count shared with the idle animation. */
export const BARS = 48;

/** Codecs that are a still picture (album art, attached cover), not a moving picture. */
const ATTACHED_PICTURE = new Set([
  "mjpeg",
  "mjpeg_2000",
  "png",
  "bmp",
  "gif",
  "webp",
  "tiff",
  "jpeg",
  "jpg",
  "apng",
]);

export function isAttachedPicture(codec: string): boolean {
  return ATTACHED_PICTURE.has(codec.toLowerCase());
}

/** A video stream the player should decode and show. Cover art is not one. */
export function isMotionVideo(kind: string, codec: string): boolean {
  return kind === "video" && !isAttachedPicture(codec);
}

/** The spectrum (analyser tap and the bar animation) runs only for a loaded audio file with no motion video. */
export function showSpectrum(loaded: boolean, hasMotionVideo: boolean, hasAudio: boolean): boolean {
  return loaded && !hasMotionVideo && hasAudio;
}

/**
 * Log-spaced peaks from an AnalyserNode byte spectrum, each 0..1.
 * Bin 0 is the DC offset and is skipped so silence doesn't lift the first bar.
 */
export function barsFromFrequency(freq: ArrayLike<number>, bars = BARS): Float32Array {
  const out = new Float32Array(bars);
  const n = freq.length;
  if (n < 2) return out;
  const usable = n - 1;
  for (let i = 0; i < bars; i++) {
    const start = 1 + Math.floor((i / bars) ** 1.7 * usable);
    const end = Math.max(start + 1, 1 + Math.floor(((i + 1) / bars) ** 1.7 * usable));
    let peak = 0;
    for (let j = start; j < end && j < n; j++) peak = Math.max(peak, freq[j] ?? 0);
    out[i] = Math.min(1, peak / 255);
  }
  return out;
}

/** Moves [current] toward [target]. A zero [dt] returns [current] unchanged, which is the paused frame. */
export function approach(current: ArrayLike<number>, target: ArrayLike<number>, dt: number, bars = BARS): Float32Array {
  const out = new Float32Array(bars);
  if (dt <= 0) {
    for (let i = 0; i < bars; i++) out[i] = current[i] ?? 0;
    return out;
  }
  for (let i = 0; i < bars; i++) {
    const c = current[i] ?? 0;
    const t = target[i] ?? 0;
    const k = t > c ? 14 : 4.5;
    const a = Math.min(1, 1 - Math.exp(-k * dt));
    out[i] = Math.min(1, Math.max(0, c + (t - c) * a));
  }
  return out;
}

/** A calm spectrum for when the samples themselves aren't available. It changes with [phase] and holds when [phase] does. */
export function idle(phase: number, bars = BARS): Float32Array {
  const out = new Float32Array(bars);
  for (let i = 0; i < bars; i++) {
    const n = bars <= 1 ? 0.5 : i / (bars - 1);
    const lobe = Math.sin(Math.PI * n);
    const flick = 0.55 + 0.45 * Math.sin(phase * 1.6 + i * 0.45);
    const slow = 0.75 + 0.25 * Math.sin(phase * 0.7 + n * 4);
    out[i] = Math.min(1, Math.max(0.04, 0.08 + 0.72 * lobe * flick * slow));
  }
  return out;
}
