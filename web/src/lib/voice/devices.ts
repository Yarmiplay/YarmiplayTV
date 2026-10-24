import type { VoiceAudioDevice } from "./engine";

/** Mic list via `enumerateDevices` (labels appear after mic permission). */
export async function listInputDevices(): Promise<VoiceAudioDevice[]> {
  return listDevices("audioinput");
}

/** Speaker list; empty beyond System default when `setSinkId` is unavailable. */
export async function listOutputDevices(): Promise<VoiceAudioDevice[]> {
  const outs = await listDevices("audiooutput");
  if (!supportsSinkId() && outs.length <= 1) {
    return [{ id: "", label: "System default", isDefault: true }];
  }
  return outs;
}

export function supportsSinkId(): boolean {
  return typeof AudioContext !== "undefined" && "setSinkId" in AudioContext.prototype;
}

async function listDevices(kind: MediaDeviceKind): Promise<VoiceAudioDevice[]> {
  const devices: VoiceAudioDevice[] = [{ id: "", label: "System default", isDefault: true }];
  if (!navigator.mediaDevices?.enumerateDevices) return devices;
  try {
    const list = await navigator.mediaDevices.enumerateDevices();
    const seen = new Set<string>();
    for (const d of list) {
      if (d.kind !== kind) continue;
      if (!d.deviceId || seen.has(d.deviceId)) continue;
      seen.add(d.deviceId);
      const label =
        d.label?.trim() ||
        (kind === "audioinput" ? `Microphone ${seen.size}` : `Speaker ${seen.size}`);
      devices.push({ id: d.deviceId, label });
    }
  } catch {
    /* permission or unsupported */
  }
  return devices;
}
