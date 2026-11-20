/// <reference lib="webworker" />
/**
 * Voice chat on the audio thread.
 *
 * - `voice-capture` cuts the microphone into exact 20 ms (960-sample) frames for the Opus encoder.
 * - `voice-playout` is the jitter buffer: each talker's decoded PCM waits in its own FIFO and the
 *   sound card's clock pulls it out, so frames that arrive in bursts (voice rides a TCP connection)
 *   still play back evenly. A talker starts once ~60 ms is queued, re-buffers with a short fade
 *   after an underrun, and drops the oldest audio if more than ~250 ms piles up.
 */

declare const sampleRate: number;
declare function registerProcessor(name: string, processor: unknown): void;
declare class AudioWorkletProcessor {
  readonly port: MessagePort;
}

const FRAME = 960;

class VoiceCapture extends AudioWorkletProcessor {
  private buf = new Float32Array(FRAME);
  private n = 0;

  process(inputs: Float32Array[][]): boolean {
    const ch = inputs[0]?.[0];
    if (!ch) return true;
    let i = 0;
    while (i < ch.length) {
      const take = Math.min(FRAME - this.n, ch.length - i);
      this.buf.set(ch.subarray(i, i + take), this.n);
      this.n += take;
      i += take;
      if (this.n === FRAME) {
        this.port.postMessage(this.buf, [this.buf.buffer]);
        this.buf = new Float32Array(FRAME);
        this.n = 0;
      }
    }
    return true;
  }
}

type PlayoutMessage =
  | { type: "pcm"; id: string; data: Float32Array }
  | { type: "forget"; id: string }
  | { type: "clear" };

const FADE = 64;
const KNEE = 0.8;

/** One talker's queued PCM. */
class Talker {
  private chunks: Float32Array[] = [];
  private offset = 0;
  buffered = 0;
  primed = false;
  /** Samples left in the fade-in after (re)starting. */
  fadeIn = 0;

  push(data: Float32Array) {
    this.chunks.push(data);
    this.buffered += data.length;
  }

  drop(samples: number) {
    let left = samples;
    while (left > 0 && this.chunks.length) {
      const head = this.chunks[0];
      const avail = head.length - this.offset;
      if (avail <= left) {
        left -= avail;
        this.buffered -= avail;
        this.chunks.shift();
        this.offset = 0;
      } else {
        this.offset += left;
        this.buffered -= left;
        left = 0;
      }
    }
  }

  /** Add up to [n] samples into [out]; returns how many were available. */
  mixInto(out: Float32Array, n: number): number {
    let written = 0;
    while (written < n && this.chunks.length) {
      const head = this.chunks[0];
      const take = Math.min(n - written, head.length - this.offset);
      for (let i = 0; i < take; i++) {
        let s = head[this.offset + i];
        if (this.fadeIn > 0) {
          s *= 1 - this.fadeIn / FADE;
          this.fadeIn--;
        }
        out[written + i] += s;
      }
      written += take;
      this.offset += take;
      this.buffered -= take;
      if (this.offset >= head.length) {
        this.chunks.shift();
        this.offset = 0;
      }
    }
    return written;
  }
}

class VoicePlayout extends AudioWorkletProcessor {
  private talkers = new Map<string, Talker>();
  private readonly start = Math.round(sampleRate * 0.06);
  private readonly max = Math.round(sampleRate * 0.25);
  private mix = new Float32Array(128);
  private tmp = new Float32Array(128);

  constructor() {
    super();
    this.port.onmessage = (event: MessageEvent<PlayoutMessage>) => {
      const msg = event.data;
      if (msg.type === "pcm") {
        let t = this.talkers.get(msg.id);
        if (!t) {
          t = new Talker();
          this.talkers.set(msg.id, t);
        }
        t.push(msg.data);
        // A backlog (a stall that delivered a burst) is dropped down to the start depth.
        if (t.buffered > this.max) t.drop(t.buffered - this.start);
      } else if (msg.type === "forget") {
        this.talkers.delete(msg.id);
      } else if (msg.type === "clear") {
        this.talkers.clear();
      }
    };
  }

  process(_inputs: Float32Array[][], outputs: Float32Array[][]): boolean {
    const out = outputs[0];
    if (!out?.length) return true;
    const n = out[0].length;
    if (this.mix.length !== n) {
      this.mix = new Float32Array(n);
      this.tmp = new Float32Array(n);
    }
    const mix = this.mix;
    const tmp = this.tmp;
    mix.fill(0);
    for (const t of this.talkers.values()) {
      if (!t.primed) {
        if (t.buffered < this.start) continue;
        t.primed = true;
        t.fadeIn = FADE;
      }
      tmp.fill(0);
      const got = t.mixInto(tmp, n);
      if (got < n) {
        // Ran dry: ease the last samples out instead of clicking, then re-buffer.
        const from = Math.max(0, got - FADE);
        for (let i = from; i < got; i++) tmp[i] *= (got - i) / (got - from + 1);
        t.primed = false;
      }
      for (let i = 0; i < n; i++) mix[i] += tmp[i];
    }
    for (let i = 0; i < n; i++) {
      // Soft knee above 0.8 (continuous, slope 1 at the knee) so overlapping talkers do not clip.
      const s = mix[i];
      const a = Math.abs(s);
      if (a > KNEE) mix[i] = Math.sign(s) * (KNEE + (1 - KNEE) * Math.tanh((a - KNEE) / (1 - KNEE)));
    }
    for (const channel of out) channel.set(mix);
    return true;
  }
}

registerProcessor("voice-capture", VoiceCapture);
registerProcessor("voice-playout", VoicePlayout);

// A module, so its ambient declarations do not clash with the player worklet's.
export {};
