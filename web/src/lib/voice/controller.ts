import { Store } from "../store";
import type { SyncplayClient, SyncplayEvent } from "../syncplay/client";
import { voiceAvailable, voiceDisabledMessage } from "../syncplay/client";
import { usesSyncplayMux, type VoicePeer } from "../syncplay/yarmiplay";
import { WebVoiceEngine } from "./engine";

export type VoiceUi = {
  available: boolean;
  connected: boolean;
  connecting: boolean;
  participantId: string | null;
  peers: VoicePeer[];
  speakingIds: Set<string>;
  selfMuted: boolean;
  transmitting: boolean;
  status: string | null;
  /** Host turned voice off — show [status] as the server message. */
  voiceDisabledOnServer: boolean;
  /** Single flip: Leave while in/joining, Join while out. */
  membershipIsLeave: boolean;
  showMic: boolean;
};

const idle = (): VoiceUi => ({
  available: false,
  connected: false,
  connecting: false,
  participantId: null,
  peers: [],
  speakingIds: new Set(),
  selfMuted: false,
  transmitting: false,
  status: null,
  voiceDisabledOnServer: false,
  membershipIsLeave: false,
  showMic: false,
});

/**
 * Joins browser voice when the server offers it.
 * Preferred: binary yv1 on `/yarmiplay/ws` when `voice.mux == syncplay`;
 * fallback: `/yarmiplay/voice` (VOICE_PROTOCOL §6.4).
 */
export class VoiceController {
  readonly ui = new Store<VoiceUi>(idle());
  private readonly engine = new WebVoiceEngine();
  private enabled = true;
  private autoJoin = true;
  private waitToJoinEmptyRoom = true;
  private autoMuteWhenPlaying = true;
  private autoUnmuteWhenPaused = true;
  /** True only when auto-mute muted us — auto-unmute never overrides a manual mute. */
  private autoMutedByPlayback = false;
  /** After Leave (or auto-join off on entry), stay out until [join]. */
  private leftByUser = false;
  private unsubs: Array<() => void> = [];
  private onDeviceLostClear: ((input: boolean) => void) | null = null;
  private playbackUnsub: (() => void) | null = null;
  private lastPlaying: boolean | null = null;
  private heardPoll: ReturnType<typeof setInterval> | null = null;

  constructor(private readonly client: () => SyncplayClient | null) {
    this.engine.onDeviceLost = (input) => this.onDeviceLostClear?.(input);
  }

  start() {
    this.stop();
    const c = this.client();
    if (!c) return;
    this.unsubs = [
      c.onEvent((ev) => void this.onEvent(ev)),
      c.subscribe(() => this.reconcile()),
    ];
    this.reconcile();
  }

  stop() {
    for (const u of this.unsubs) u();
    this.unsubs = [];
    this.playbackUnsub?.();
    this.playbackUnsub = null;
    this.lastPlaying = null;
    this.autoMutedByPlayback = false;
    if (this.heardPoll) clearInterval(this.heardPoll);
    this.heardPoll = null;
    this.engine.disconnect();
    this.leftByUser = false;
    this.ui.set(idle());
  }

  setEnabled(on: boolean) {
    this.enabled = on;
    this.reconcile();
  }

  setAutoJoin(on: boolean) {
    this.autoJoin = on;
    this.reconcile();
  }

  setWaitToJoinEmptyRoom(on: boolean) {
    this.waitToJoinEmptyRoom = on;
    this.reconcile();
  }

  setNoiseSuppression(on: boolean) {
    this.engine.setNoiseSuppression(on);
  }

  setAutoMuteWhenPlaying(on: boolean) {
    this.autoMuteWhenPlaying = on;
  }

  setAutoUnmuteWhenPaused(on: boolean) {
    this.autoUnmuteWhenPaused = on;
  }

  /**
   * Observe player pause/play for auto-mute / auto-unmute (same semantics as the apps).
   * Pass a subscribe that yields `{ loaded, paused }`.
   */
  bindPlayback(subscribe: (cb: (s: { loaded: boolean; paused: boolean }) => void) => () => void) {
    this.playbackUnsub?.();
    this.lastPlaying = null;
    this.playbackUnsub = subscribe((s) => {
      const playing = s.loaded && !s.paused;
      if (this.lastPlaying === playing) return;
      this.lastPlaying = playing;
      this.onPlaybackPlayingChanged(playing);
    });
  }

  setOnDeviceLost(handler: ((input: boolean) => void) | null) {
    this.onDeviceLostClear = handler;
  }

  listInputDevices() {
    return this.engine.listInputDevices();
  }

  listOutputDevices() {
    return this.engine.listOutputDevices();
  }

  setInputDeviceId(id: string | null) {
    this.engine.setInputDeviceId(id);
  }

  setOutputDeviceId(id: string | null) {
    this.engine.setOutputDeviceId(id);
  }

  setSelfMuted(muted: boolean) {
    this.autoMutedByPlayback = false; // manual
    this.applySelfMuted(muted);
  }

  private applySelfMuted(muted: boolean) {
    this.engine.setSelfMuted(muted);
    const transmitting = muted ? false : this.ui.value.transmitting || (!muted && this.ui.value.connected);
    this.ui.update((u) => ({ ...u, selfMuted: muted, transmitting: muted ? false : transmitting }));
    if (!muted && transmitting) this.engine.setTransmitting(true);
    this.client()?.sendVoiceActivity(!muted && transmitting);
  }

  private onPlaybackPlayingChanged(playing: boolean) {
    const ui = this.ui.value;
    if (!ui.connected || !ui.showMic) return;
    if (playing) {
      if (!this.autoMuteWhenPlaying) return;
      if (ui.selfMuted) return;
      this.applySelfMuted(true);
      this.autoMutedByPlayback = true;
    } else {
      if (!this.autoUnmuteWhenPaused) return;
      if (!this.autoMutedByPlayback) return;
      this.applySelfMuted(false);
      this.autoMutedByPlayback = false;
    }
  }

  setTransmitting(on: boolean) {
    if (this.ui.value.selfMuted) return;
    this.engine.setTransmitting(on);
    this.ui.update((u) => ({ ...u, transmitting: on }));
    this.client()?.sendVoiceActivity(on);
  }

  leave() {
    this.leftByUser = true;
    this.client()?.leaveVoice();
    this.engine.disconnect();
    this.ui.update((u) => ({
      ...u,
      connected: false,
      connecting: false,
      participantId: null,
      peers: [],
      speakingIds: new Set(),
      transmitting: false,
      status: "Left voice",
      membershipIsLeave: false,
      showMic: false,
    }));
  }

  /** Rejoin after Leave / when auto-join is off. Respects client and server toggles. */
  join() {
    const c = this.client();
    const y = c?.state.yarmiplay;
    if (!c || !y || !voiceAvailable(y) || !this.enabled) return;
    this.leftByUser = false;
    this.ui.update((u) => ({
      ...u,
      connecting: true,
      status: "Connecting voice…",
      membershipIsLeave: true,
      showMic: false,
    }));
    c.joinVoice();
  }

  setPeerMuted(participantId: string, muted: boolean) {
    this.engine.setPeerMuted(participantId, muted);
  }

  setPeerVolume(participantId: string, volume: number) {
    this.engine.setPeerVolume(participantId, volume);
  }

  private reconcile() {
    const c = this.client();
    const y = c?.state.yarmiplay;
    const disabledMsg = y ? voiceDisabledMessage(y) : null;
    const available = !!y && voiceAvailable(y) && this.enabled;
    this.ui.update((u) => ({
      ...u,
      available,
      voiceDisabledOnServer: !!disabledMsg && this.enabled,
      status: disabledMsg && this.enabled ? disabledMsg : u.status,
    }));
    if (!available) {
      this.leftByUser = false;
      if (this.ui.value.connected || this.ui.value.participantId || this.ui.value.connecting) {
        c?.leaveVoice();
        this.engine.disconnect();
        this.ui.update(() => ({
          ...idle(),
          available: false,
          voiceDisabledOnServer: !!disabledMsg && this.enabled,
          status: disabledMsg && this.enabled ? disabledMsg : null,
        }));
      }
      return;
    }
    if (this.leftByUser) {
      this.ui.update((u) => ({
        ...u,
        membershipIsLeave: false,
        showMic: false,
        status: u.status ?? "Not in voice — tap Join voice to talk",
      }));
      return;
    }
    if (this.ui.value.connected || this.ui.value.connecting) return;
    const hasOthers = (c?.state.users.filter((u) => u.name !== c.state.username).length ?? 0) > 0;
    if (this.autoJoin && (hasOthers || !this.waitToJoinEmptyRoom)) {
      this.ui.update((u) => ({
        ...u,
        connecting: true,
        status: "Connecting voice…",
        membershipIsLeave: true,
        showMic: false,
      }));
      c?.joinVoice();
    } else if (this.autoJoin && this.waitToJoinEmptyRoom) {
      this.ui.update((u) => ({
        ...u,
        connected: false,
        connecting: false,
        membershipIsLeave: false,
        showMic: false,
        status: "Room is empty — voice will connect when someone joins",
      }));
    } else {
      this.ui.update((u) => ({
        ...u,
        connected: false,
        connecting: false,
        membershipIsLeave: false,
        showMic: false,
        status: u.status ?? "Not in voice — tap Join voice to talk",
      }));
    }
  }

  private async onEvent(ev: SyncplayEvent) {
    switch (ev.type) {
      case "voiceJoined": {
        const c = this.client();
        if (!c) return;
        const useMux = usesSyncplayMux(c.state.yarmiplay.voice) || ev.mux === "syncplay";
        const ok = await this.engine.connect(
          {
            host: c.config.host,
            port: typeof ev.port === "number" ? ev.port : c.config.port,
            secure: c.config.useTls,
            token: ev.token,
            key: ev.key,
            wsPath: ev.wsPath,
            maxBitrate: ev.maxBitrate,
            mux: useMux
              ? {
                  send: (packet) => c.sendVoiceMuxPacket(packet),
                  setOnPacket: (handler) => c.setVoiceMuxHandler(handler),
                }
              : null,
          },
          (speaking) => c.sendVoiceActivity(speaking),
        );
        if (!ok) {
          c.leaveVoice();
          this.ui.update((u) => ({
            ...u,
            connected: false,
            connecting: false,
            status: "Couldn't connect voice",
            membershipIsLeave: false,
            showMic: false,
          }));
          return;
        }
        this.leftByUser = false;
        this.ui.update((u) => ({
          ...u,
          connected: true,
          connecting: false,
          participantId: ev.participantId,
          status: null,
          transmitting: !u.selfMuted,
          membershipIsLeave: true,
          showMic: true,
        }));
        this.engine.setTransmitting(!this.ui.value.selfMuted);
        this.startHeardPoll();
        break;
      }
      case "voiceClosed":
        if (this.heardPoll) clearInterval(this.heardPoll);
        this.heardPoll = null;
        this.engine.disconnect();
        this.ui.update((u) => ({
          ...u,
          connected: false,
          connecting: false,
          participantId: null,
          peers: [],
          speakingIds: new Set(),
          transmitting: false,
          showMic: false,
          membershipIsLeave: false,
          voiceDisabledOnServer: ev.reason === "disabled",
          status:
            ev.reason === "full"
              ? "Voice room is full"
              : ev.reason === "disabled"
                ? ev.message?.trim() || "voice disabled on this server"
                : ev.reason === "vanilla"
                  ? "Voice chat turned off on the server"
                  : ev.reason === "left"
                    ? "Left voice"
                    : null,
        }));
        if (ev.reason === "room") {
          this.leftByUser = false;
          if (this.enabled) this.reconcile();
        }
        break;
      case "voicePeers":
        // speakingIds stay driven by locally heard decode energy (instant clear on silence).
        this.ui.update((u) => ({ ...u, peers: ev.peers }));
        break;
      case "voiceActivity":
        break;
      case "disconnected":
      case "sessionEnded":
        this.engine.disconnect();
        this.leftByUser = false;
        this.ui.set(idle());
        break;
      default:
        break;
    }
  }

  /** 0…1 post-capture mic level for the activity ring. */
  captureLevel(): number {
    return this.engine.captureLevel();
  }

  /** Poll receive energy so speaking names drop the same tick audio stops (no linger). */
  private startHeardPoll() {
    if (this.heardPoll) clearInterval(this.heardPoll);
    this.heardPoll = setInterval(() => {
      if (!this.ui.value.connected) return;
      const heard = this.engine.audibleParticipantIds();
      const cur = this.ui.value.speakingIds;
      if (heard.size === cur.size && [...heard].every((id) => cur.has(id))) return;
      this.ui.update((u) => ({ ...u, speakingIds: heard }));
    }, 33);
  }
}
