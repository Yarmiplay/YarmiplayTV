import { decodeKeyBase64, open, parseTokenHex, seal, YV1 } from "./yv1";

export type VoiceJoinedSeat = {
  host: string;
  port: number;
  /** Use wss when the Syncplay session is already on TLS. */
  secure: boolean;
  token: string;
  key: string;
  wsPath: string;
  maxBitrate?: number;
};

/**
 * Browser voice media: binary WebSocket to `/yarmiplay/voice?t=<token>`, AES-GCM `yv1`
 * frames, Opus via WebCodecs when available.
 */
export class WebVoiceEngine {
  private ws: WebSocket | null = null;
  private key: Uint8Array | null = null;
  private token: bigint | null = null;
  private seq = (Math.random() * 0xffffffff) >>> 0;
  private timestamp = 0;
  private keepaliveTimer: ReturnType<typeof setInterval> | null = null;
  private transmitting = false;
  private selfMuted = false;
  private closed = true;
  private captureCtx: AudioContext | null = null;
  private captureNode: AudioWorkletNode | ScriptProcessorNode | null = null;
  private captureStream: MediaStream | null = null;
  private encoder: AudioEncoder | null = null;
  private readonly decoders = new Map<string, AudioDecoder>();
  private playCtx: AudioContext | null = null;
  private peerGain = new Map<string, { muted: boolean; volume: number }>();
  private outputGain = 1;
  private inputGain = 1;
  private onSpeaking: ((speaking: boolean) => void) | null = null;

  async connect(seat: VoiceJoinedSeat, onSpeaking?: (speaking: boolean) => void): Promise<boolean> {
    this.disconnect();
    this.onSpeaking = onSpeaking ?? null;
    const token = parseTokenHex(seat.token);
    const key = decodeKeyBase64(seat.key);
    if (token === null || !key) return false;
    this.token = token;
    this.key = key;
    this.closed = false;
    const path = seat.wsPath.startsWith("/") ? seat.wsPath : `/${seat.wsPath}`;
    const url = `${seat.secure ? "wss" : "ws"}://${seat.host}:${seat.port}${path}?t=${seat.token}`;
    try {
      await this.openSocket(url);
      await this.sendKeepalive();
      this.keepaliveTimer = setInterval(() => void this.sendKeepalive(), 5000);
      await this.startCapture(seat.maxBitrate ?? 32_000);
      return true;
    } catch {
      this.disconnect();
      return false;
    }
  }

  disconnect() {
    this.closed = true;
    if (this.keepaliveTimer) clearInterval(this.keepaliveTimer);
    this.keepaliveTimer = null;
    void this.sendBye();
    try {
      this.ws?.close();
    } catch {
      /* ignore */
    }
    this.ws = null;
    this.stopCapture();
    for (const d of this.decoders.values()) {
      try {
        d.close();
      } catch {
        /* ignore */
      }
    }
    this.decoders.clear();
    try {
      void this.playCtx?.close();
    } catch {
      /* ignore */
    }
    this.playCtx = null;
    this.key = null;
    this.token = null;
    this.transmitting = false;
  }

  setTransmitting(on: boolean) {
    this.transmitting = on && !this.selfMuted;
    this.onSpeaking?.(this.transmitting);
  }

  setSelfMuted(muted: boolean) {
    this.selfMuted = muted;
    if (muted) this.transmitting = false;
    this.onSpeaking?.(this.transmitting);
  }

  setInputGain(gain: number) {
    this.inputGain = Math.min(2, Math.max(0, gain));
  }

  setOutputGain(gain: number) {
    this.outputGain = Math.min(2, Math.max(0, gain));
  }

  setPeerMuted(participantId: string, muted: boolean) {
    const cur = this.peerGain.get(participantId) ?? { muted: false, volume: 1 };
    this.peerGain.set(participantId, { ...cur, muted });
  }

  setPeerVolume(participantId: string, volume: number) {
    const cur = this.peerGain.get(participantId) ?? { muted: false, volume: 1 };
    this.peerGain.set(participantId, { ...cur, volume: Math.min(2, Math.max(0, volume)) });
  }

  private openSocket(url: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url);
      ws.binaryType = "arraybuffer";
      ws.onopen = () => {
        this.ws = ws;
        resolve();
      };
      ws.onerror = () => reject(new Error("voice websocket failed"));
      ws.onclose = () => {
        if (!this.closed) this.disconnect();
      };
      ws.onmessage = (ev) => {
        if (typeof ev.data === "string") return;
        void this.onPacket(new Uint8Array(ev.data as ArrayBuffer));
      };
    });
  }

  private async onPacket(raw: Uint8Array) {
    if (!this.key) return;
    const opened = await open(this.key, raw);
    if (!opened || opened.type !== YV1.TYPE_AUDIO || opened.plain.length === 0) return;
    const senderKey = `c${opened.sender.toString()}`;
    const pref = this.peerGain.get(senderKey);
    if (pref?.muted) return;
    await this.playOpus(senderKey, opened.plain, (pref?.volume ?? 1) * this.outputGain);
  }

  private async sendPacket(type: number, plain: Uint8Array) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !this.key || this.token === null) return;
    const seq = this.seq++ >>> 0;
    const packet = await seal(this.key, type, this.token, 0n, seq, this.timestamp >>> 0, plain);
    if (packet.length < YV1.MIN_PACKET || packet.length > YV1.MAX_PACKET) return;
    this.ws.send(packet);
  }

  private async sendKeepalive() {
    await this.sendPacket(YV1.TYPE_KEEPALIVE, new Uint8Array([0]));
  }

  private async sendBye() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !this.key || this.token === null) return;
    try {
      await this.sendPacket(YV1.TYPE_BYE, new Uint8Array(0));
    } catch {
      /* ignore */
    }
  }

  private async startCapture(bitrate: number) {
    if (typeof AudioEncoder === "undefined") return;
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, sampleRate: 48000, echoCancellation: true, noiseSuppression: true },
      video: false,
    });
    this.captureStream = stream;
    const ctx = new AudioContext({ sampleRate: 48000 });
    this.captureCtx = ctx;
    const source = ctx.createMediaStreamSource(stream);
    const encoder = new AudioEncoder({
      output: (chunk) => {
        if (!this.transmitting || this.selfMuted) return;
        const buf = new Uint8Array(chunk.byteLength);
        chunk.copyTo(buf);
        this.timestamp = (this.timestamp + 960) >>> 0;
        void this.sendPacket(YV1.TYPE_AUDIO, buf);
      },
      error: () => {
        /* ignore encode errors */
      },
    });
    encoder.configure({
      codec: "opus",
      sampleRate: 48000,
      numberOfChannels: 1,
      bitrate: Math.min(128_000, Math.max(6_000, bitrate)),
    });
    this.encoder = encoder;

    // 20 ms frames via ScriptProcessor (widely available); AudioWorklet would be nicer later.
    const proc = ctx.createScriptProcessor(960, 1, 1);
    proc.onaudioprocess = (ev) => {
      if (!this.encoder || this.encoder.state !== "configured") return;
      if (!this.transmitting || this.selfMuted) return;
      const input = ev.inputBuffer.getChannelData(0);
      const interleaved = new Float32Array(input.length);
      for (let i = 0; i < input.length; i++) {
        interleaved[i] = Math.max(-1, Math.min(1, input[i] * this.inputGain));
      }
      try {
        this.encoder.encode(
          new AudioData({
            format: "f32",
            sampleRate: 48000,
            numberOfFrames: interleaved.length,
            numberOfChannels: 1,
            timestamp: 0,
            data: interleaved,
          }),
        );
      } catch {
        /* ignore */
      }
    };
    source.connect(proc);
    proc.connect(ctx.destination); // required for some browsers; muted via gain 0
    const mute = ctx.createGain();
    mute.gain.value = 0;
    proc.disconnect();
    source.connect(proc);
    proc.connect(mute);
    mute.connect(ctx.destination);
    this.captureNode = proc;
  }

  private stopCapture() {
    try {
      this.encoder?.close();
    } catch {
      /* ignore */
    }
    this.encoder = null;
    try {
      this.captureNode?.disconnect();
    } catch {
      /* ignore */
    }
    this.captureNode = null;
    this.captureStream?.getTracks().forEach((t) => t.stop());
    this.captureStream = null;
    try {
      void this.captureCtx?.close();
    } catch {
      /* ignore */
    }
    this.captureCtx = null;
  }

  private async playOpus(senderKey: string, opus: Uint8Array, gain: number) {
    if (typeof AudioDecoder === "undefined") return;
    if (!this.playCtx) this.playCtx = new AudioContext({ sampleRate: 48000 });
    let decoder = this.decoders.get(senderKey);
    if (!decoder) {
      const ctx = this.playCtx;
      decoder = new AudioDecoder({
        output: (audio) => {
          const frames = audio.numberOfFrames;
          const data = new Float32Array(frames);
          audio.copyTo(data, { planeIndex: 0 });
          audio.close();
          const buffer = ctx.createBuffer(1, frames, 48000);
          const channel = buffer.getChannelData(0);
          for (let i = 0; i < frames; i++) channel[i] = data[i] * gain;
          const src = ctx.createBufferSource();
          src.buffer = buffer;
          src.connect(ctx.destination);
          src.start();
        },
        error: () => {
          /* ignore */
        },
      });
      decoder.configure({ codec: "opus", sampleRate: 48000, numberOfChannels: 1 });
      this.decoders.set(senderKey, decoder);
    }
    try {
      decoder.decode(
        new EncodedAudioChunk({
          type: "key",
          timestamp: 0,
          data: opus,
        }),
      );
    } catch {
      /* ignore */
    }
  }
}
