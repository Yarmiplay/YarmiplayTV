import { Store } from "../store";
import type { SyncplayClient, SyncplayEvent } from "../syncplay/client";
import { voiceAvailable } from "../syncplay/client";
import type { VoicePeer } from "../syncplay/yarmiplay";
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
  membershipIsLeave: false,
  showMic: false,
});

/**
 * Joins browser voice when the server offers it. Media uses `/yarmiplay/voice` (VOICE_PROTOCOL §6.4).
 */
export class VoiceController {
  readonly ui = new Store<VoiceUi>(idle());
  private readonly engine = new WebVoiceEngine();
  private enabled = true;
  private autoJoin = true;
  /** After Leave (or auto-join off on entry), stay out until [join]. */
  private leftByUser = false;
  private unsubs: Array<() => void> = [];
  private onDeviceLostClear: ((input: boolean) => void) | null = null;

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
    this.engine.setSelfMuted(muted);
    this.ui.update((u) => ({ ...u, selfMuted: muted, transmitting: muted ? false : u.transmitting }));
    this.client()?.sendVoiceActivity(!muted && this.ui.value.transmitting);
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
    const available = !!y && voiceAvailable(y) && this.enabled;
    this.ui.update((u) => ({ ...u, available }));
    if (!available) {
      this.leftByUser = false;
      if (this.ui.value.connected || this.ui.value.participantId || this.ui.value.connecting) {
        c?.leaveVoice();
        this.engine.disconnect();
        this.ui.update(() => ({ ...idle(), available: false }));
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
    if (this.autoJoin) {
      this.ui.update((u) => ({
        ...u,
        connecting: true,
        status: "Connecting voice…",
        membershipIsLeave: true,
        showMic: false,
      }));
      c?.joinVoice();
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
        const ok = await this.engine.connect(
          {
            host: c.config.host,
            port: c.config.port,
            secure: c.config.useTls,
            token: ev.token,
            key: ev.key,
            wsPath: ev.wsPath,
            maxBitrate: ev.maxBitrate,
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
        break;
      }
      case "voiceClosed":
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
          status:
            ev.reason === "full"
              ? "Voice room is full"
              : ev.reason === "disabled" || ev.reason === "vanilla"
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
        this.ui.update((u) => ({
          ...u,
          peers: ev.peers,
          speakingIds: new Set(ev.peers.filter((p) => p.speaking).map((p) => p.participantId)),
        }));
        break;
      case "voiceActivity":
        this.ui.update((u) => {
          const next = new Set(u.speakingIds);
          if (ev.speaking) next.add(ev.participantId);
          else next.delete(ev.participantId);
          return { ...u, speakingIds: next };
        });
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
}
