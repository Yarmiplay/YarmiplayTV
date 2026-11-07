# YarmiplayTV 2.7.0 — voice chat implementation plan

**Status:** planning only (no feature code).  
**Companion:** shared owner plan (`voice-chat-plan.md`).  
**Server:** YarmiplayServerTV 2.0.0 drafts the voice protocol in parallel; this client implements against that spec.

---

## 0. Current codebase anchors (what we extend)

| Area | Where |
|---|---|
| Syncplay + Yarmiplay extension | `syncplay-protocol` — `SyncplayClient`, `YarmiplayCapabilities`, `Yarmiplay` messages, `RoomUser`, `RoomState` |
| Room / chat mute (text only today) | `SyncController.showChat` / `block()` — in-memory block; **must stay separate from voice mute** |
| Settings | `SettingsStore` / `AppSettings` (DataStore); TV `SettingsScreen` SYNCING; mobile `MobileSettings.SyncingSettings` |
| Room people UI | TV `RoomPanel` / `RoomUserRow`; shared `RoomUsersContent`; desktop `DesktopRoomPanel` |
| Audio today | Android/desktop **mpv** only; no `RECORD_AUDIO`, no WebRTC |
| Capability handshake | `features.yarmiplay.capabilities` via `parseCapabilities()` — add `voice` the same way as `roomSettings` |
| Session token / HTTP | `YarmiplaySession` bearer on Syncplay host:port — natural auth for a voice channel |
| Windows update UI style | `LaunchUpdateWindow` (Swing card) + `WindowsUpdater` (`msiexec /passive`) |
| Privacy / Play | `docs/privacy.md`, `docs/play/README.md` |

There is **no** mic/WebRTC code today. Vanilla Syncplay already ignores unknown top-level commands and unknown capability flags (defaults false).

---

## 1. Architecture (client)

### 1.1 Recommendation: SFU Opus relay (not P2P WebRTC)

**Preferred media path:** Opus audio frames to/from YarmiplayServerTV acting as an SFU (forwarding only; never records). Signalling and capability discovery use the existing Yarmiplay Syncplay extension; media uses a **separate** authenticated channel (WSS and/or DTLS/UDP) on the same host, bound to the existing session token.

**Why not P2P WebRTC:** room-sized groups + home NATs; plan already wants server relay.  
**Why not require full WebRTC on every client:** Kotlin Multiplatform WebRTC (e.g. webrtc-kmp) does **not** cover desktop JVM. Shipping libwebrtc JNI on Windows/Linux/macOS is heavy next to libmpv. A shared Opus encode/decode path (e.g. Kopus or bundled libopus) plus platform capture/render keeps Android and desktop aligned and matches an SFU that only forwards packets.

**When to use WebRTC anyway:** if the server protocol standardizes on a WebRTC SFU (mediasoup / LiveKit-style). Then:

| Platform | Stack |
|---|---|
| Android | `io.getstream:stream-webrtc-android` (or Google AAR) |
| Desktop | Evaluate Java WebRTC bindings **or** still send Opus over the SFU’s non-WebRTC ingest if offered |

**Client module sketch (later build phase):**

- `voice` (or under `shared`): `VoiceController`, settings, mute/volume state, speaking map, join/leave sounds.
- `voice-android` / `voice-desktop`: capture, render, device enumeration, permission prompts.
- Wire from `AppContainer` when `room.yarmiplay.capabilities.voice && settings.voiceChat && serverVoiceEnabled`.

### 1.2 Capability detection (vanilla Syncplay)

1. Hello / session: parse `capabilities.voice` (bool; default **false** on old servers).
2. Optional: server advertises `voiceEnabled` room/server toggle inside Yarmiplay status (server plan: on by default).
3. Client setting `voiceChat` (default **on**).
4. Effective: `voiceActive = caps.voice && serverVoiceOn && clientVoiceOn && sessionPresent`.
5. If not active: **no** voice socket, **no** voice UI, zero Syncplay traffic about voice.

Peers: optional `features` / presence flag “has voice” so UI can show listen-only vs talking-capable without guessing.

### 1.3 Connection lifecycle

1. Syncplay CONNECTED + Yarmiplay session token issued.
2. If `voiceActive`, open voice transport (reconnect with Syncplay reconnect; backoff).
3. Publish local mute/talk-mode/speaking; subscribe to peer speaking + join/leave.
4. On disconnect / vanilla fallback / toggles off: tear down cleanly; stop capture.

Encrypted transport as required by server (WSS or DTLS-SRTP). Client never writes voice to disk.

---

## 2. Audio stacks per platform

### 2.1 Android phone / tablet

| Role | Approach |
|---|---|
| Capture | `AudioRecord` with `VOICE_COMMUNICATION` (AEC/NS when available) → PCM → Opus |
| Render | Dedicated `AudioTrack` (voice usage) mixed with system; do **not** route voice through mpv |
| Focus | Request audio focus appropriate for communication; avoid forcing `MODE_IN_COMMUNICATION` if it ducks mpv badly — measure on device |
| Permission | `RECORD_AUDIO` runtime; first-use rationale before talk (listen-only needs no mic permission) |
| Background | If talking while screen off: may need `FOREGROUND_SERVICE_MICROPHONE` in addition to existing mediaPlayback service — decide after product choice (prefer: pause TX when app backgrounded unless explicitly allowed) |

### 2.2 Android TV

| Role | Approach |
|---|---|
| Listen | Always (when voice active): per-user mute/volume, speaking indicators, join/leave sounds |
| Talk | See §3 feasibility; default **PTT**; open-mic only if a continuous input device exists (USB/BT headset) |
| Feature manifest | `<uses-feature android:name="android.hardware.microphone" android:required="false"/>` **required** because `RECORD_AUDIO` implies microphone |

### 2.3 Windows desktop

| Role | Approach |
|---|---|
| Capture / render | WASAPI (or PortAudio wrapper) for device enum + exclusive/shared capture |
| Codec | Same Opus path as Android |
| Permissions | Windows microphone privacy; handle denial with clear Settings copy |
| Conflict | mpv uses wasapi; keep voice on a separate client stream |

### 2.4 Linux desktop

| Role | Approach |
|---|---|
| Capture / render | PipeWire/PulseAudio (PortAudio or native) |
| Packaging | Flatpak already `--socket=pulseaudio`; Snap today has only `audio-playback` → add **`audio-record`** |

### 2.5 VAD and PTT

- **Open mic + VAD (default on phone/desktop):** energy/WebRTC-VAD style gate; hysteresis; “speaking” only when gated open.
- **PTT:** TX only while key/button held; UI shows “Talk” affordance; VAD optional for speaking indicator while held.
- Settings: `VoiceTalkMode { OpenMic, PushToTalk }`.

---

## 3. Android TV remote mic — feasibility

### 3.1 Verdict

**Partially yes, with strong device variance. Ship listen-only as a first-class mode; treat remote PTT as best-effort.**

Android documentation states TV **controller** microphones are supported even though `android.hardware.microphone` is not a TV system feature. Third-party apps can capture when:

1. `RECORD_AUDIO` is granted (runtime).
2. `android.hardware.microphone` is declared **`required="false"`** (otherwise Play filters TVs).
3. Capture uses an audio source that routes to the remote’s voice channel — commonly **`MediaRecorder.AudioSource.VOICE_RECOGNITION`** rather than `MIC` (field reports on Google TV).
4. Some remotes only power the mic while a voice/assistant session is active; OEM behaviour differs (Google TV vs Fire TV vs others).

`SpeechRecognizer` is **not** a good primary path for WebRTC/Opus chat (it yields transcripts / closed audio, not a clean realtime PCM pipe for a custom codec). Prefer `AudioRecord`/`AudioSource.VOICE_RECOGNITION` for PTT PCM.

### 3.2 Proposed TV talk paths (priority order)

| # | Path | Behaviour |
|---|---|---|
| A | **PTT + AudioRecord** | Map a hold action (e.g. long-press OK / dedicated mic key if delivered to the app) → start `VOICE_RECOGNITION` capture → Opus while held → stop on release |
| B | **USB / Bluetooth headset mic** | Enumerate `AudioDeviceInfo` inputs; treat as normal mic; allow open-mic or PTT |
| C | **OEM wake assist (experimental)** | If A yields silence, try briefly priming the voice input path (device-specific; may be unacceptable UX / policy) |
| D | **Listen-only** | Banner: “Listening only — no mic on this TV. Join from your phone to talk.” |

Emulators are **not** valid for remote-mic proof. Real-device checks go through the coordinator.

### 3.3 TV UX specifics

- Voice controls in Room panel (Users): self-mute, talk mode (if mic), per-user mute/volume.
- Player overlay: compact speaking dots / names; optional PTT hint when mode is PTT.
- D-pad: local mute and PTT must be reachable without touch.
- Never require phone-to-TV pairing (per shared plan).

---

## 4. UI / settings (all clients)

### 4.1 Settings (new)

Under **Syncing** (next to chat toggles):

- **Voice chat** (master client toggle, default on)
- **Talk mode:** Open mic (VAD) / Push to talk
- **Input volume** / **Output volume** (voice path gains)
- **Microphone** / **Output device** (desktop; Android when multiple inputs)
- **Join / leave sounds** on/off + volume

TV: same via `ToggleRow` / `ValueRow`. Mobile/desktop: `SettingsToggle` + sliders.

### 4.2 Room people row

Extend `RoomUserRow` / `RoomUsersContent` (keep chat Block separate):

- Speaking indicator (animated / accent dot)
- Local voice-mute toggle (icon)
- Per-user volume control (slider or stepped buttons on TV)
- Self row: self-mute + PTT state

### 4.3 State model (sketch)

```text
VoiceSettings: enabled, talkMode, inputGain, outputGain, inputDeviceId?, outputDeviceId?, joinLeaveSounds, joinLeaveVolume
VoicePeerLocal: mutedLocal, volume   // keyed by Syncplay username; session-scoped (see open questions)
VoiceRuntime: connected, selfMuted, speakingSelf, speakingPeers[], canCapture, captureReason
```

Persist `VoiceSettings` in `SettingsStore`. Peer local mute/volume: default **session-only** (like chat block), unless product asks otherwise.

---

## 5. Windows custom installer (non–MS Store)

### 5.1 Goals

- Replace default **jpackage MSI** UX with a **custom-branded** installer for download-page / winget.
- **Microsoft Store MSIX** unchanged (`desktop/msix`, `make-msix.ps1`).
- Unattended/silent installs show a **small window styled like `LaunchUpdateWindow`** (borderless Swing card: logo, title, progress, brand palette).
- Auto-update continues to work; **upgrade from existing MSI installs must succeed**.

### 5.2 Proposed shape

1. **Bootstrapper `.exe`** (Rust or small JVM/Swing, matching server’s installer direction for brand consistency):  
   - Copies/updates files under the current per-user install location.  
   - Writes shortcuts / uninstall registry consistent with today.  
   - Shows branded progress UI always for UI installs; for `/S` / `--silent` still shows the small progress card (per plan), not a fully invisible install.
2. **Upgrade path from MSI:**  
   - Detect product via existing UpgradeCode `6F0C2B1E-4E59-4C1B-9B7E-2D1A5C3F8E41` / uninstall key.  
   - Prefer in-place upgrade of the same per-user directory; migrate nothing from `%APPDATA%\YarmiplayTV` (already stable).  
   - Optionally uninstall old MSI entry after files replaced so Add/Remove Programs shows one product.
3. **Updater changes (`WindowsUpdater`):**  
   - Download `.exe` (or keep wrapping MSI if bootstrapper is exe-from-msi — prefer single `.exe` artifact).  
   - Replace `msiexec /i … /passive` with `Start-Process installer.exe --silent --relaunch …`.  
   - `version.json` Windows entry points at the new artifact; sha256 unchanged pattern.
4. **CI / SignPath / winget:** update `release.yml`, SignPath artifact config, winget installer type/URL; portable zip can stay as today.

Coordinate branding/flags with YarmiplayServerTV’s installer so both products feel like one family.

---

## 6. Privacy, Play data-safety, permissions

### 6.1 `docs/privacy.md`

Add explicit bullets:

- Microphone audio is used **only** for optional room voice chat on YarmiplayServerTV when voice is enabled on server and client.
- Audio is streamed **ephemerally** through that server to other people in the room; **not recorded or stored** by the app or (per server plan) the server.
- Vanilla Syncplay servers never receive voice.
- Android: microphone permission requested when the user first tries to talk (listening alone does not require it).
- Desktop: OS microphone permission as applicable.

Update “Permissions (Android)” list; refresh effective date.

### 6.2 Play Console (`docs/play/README.md`)

- Data safety: declare **Audio** (voice or sound recordings) as **shared** (not collected by the developer), purpose App functionality, processed ephemerally, encrypted in transit on the voice path (call out that Syncplay itself may still be plaintext on stock servers — voice only on our TLS/DTLS path).
- Permissions declarations: note `RECORD_AUDIO`; keep microphone feature optional for TV.
- Reviewer notes: voice chat in rooms; user can disable; local mute; no permanent recording.

### 6.3 Manifest / packaging

- `RECORD_AUDIO`; `microphone` required=false.
- Consider `MODIFY_AUDIO_SETTINGS` only if needed for routing.
- Snap: `audio-record`. Flatpak: already pulse.
- Manual / terms: short voice section if community rules need it.

---

## 7. Test plan

### 7.1 Automated (CI)

| Layer | Cases |
|---|---|
| `syncplay-protocol` | `capabilities.voice` parse; absent ⇒ false; vanilla Hello unchanged (`YarmiplayClientTest` style) |
| Voice unit | Mute/volume matrix; VAD gate; PTT press/release; “effective voice off” matrix (server/client/caps) |
| Fake SFU | Scripted server forwarding Opus fixtures between 2–3 clients (synthetic PCM → Opus) |
| UI | Settings toggles; room row local mute separate from chat block; voice UI hidden when inactive (`RoomPanelUiTest` / TV tags) |
| Windows updater | Installer invocation args; silent UI smoke (`LaunchUpdateWindowTest` patterns) |

Skip heavy emulator voice capture. No real-ear assertions in CI.

### 7.2 Real devices (via coordinator → user)

- Phone + desktop open-mic and PTT in one room.
- TV listen-only UX; TV + headset talk; TV remote PTT on **named** devices.
- mpv playback + voice simultaneously (duck/echo check).
- Vanilla Syncplay server: no voice UI, no regressions.
- Server voice toggle off / client toggle off.
- Windows: MSI → custom installer upgrade; silent install UI; auto-update from 2.6.x → 2.7.0.

---

## 8. What we need from the server protocol

Minimum for client implementation:

1. **`capabilities.voice`** (and how server toggle appears in Hello/status).
2. **Auth binding** to existing device session / bearer token (no second password).
3. **Transport:** URL/path or port; WSS vs UDP/DTLS; ICE needed or not (prefer not, if all media is server-relayed).
4. **Codec contract:** Opus mode (Voip), sample rate (prefer 48 kHz mono), frame size (e.g. 20 ms), bitrate range, packet framing (length-prefixed / RTP-like).
5. **Signalling messages:** join voice room, leave, speaking start/stop (or client-only VAD with optional server hint), error codes.
6. **SFU rules:** fan-out to all other voice participants; confirm **no server-side mute** (local only).
7. **Limits:** max bitrate, max participants, idle timeout, reconnect behaviour.
8. **Versioning:** how newer servers talk to older clients (ignore voice).
9. **Test hooks:** optional loopback or fixture mode for CI.

Nice-to-have: per-peer “capturing” flag; server-estimated levels (we can do client-side).

---

## 9. Implementation phases (after protocol exists)

1. **Protocol adapters + capability** in `syncplay-protocol` / `SyncController` (no audio yet).
2. **VoiceController + settings + UI shell** (hidden when inactive).
3. **Desktop capture/render + Opus** against a local/fake SFU.
4. **Android phone/tablet** path + permissions.
5. **TV listen-only + headset; then remote PTT experiments** on real hardware.
6. **Sounds, speaking indicators, polish.**
7. **Windows custom installer + updater + winget/SignPath.**
8. **Privacy / Play / Snap docs + listings.**
9. **Version bump 2.7.0**, safety-net (skip heavy TV emulator voice), tag release.

Build order per shared plan: **server first → YarmiplayTV → Noire merge**.

---

## 10. Open questions for the user

1. Confirm **Opus-over-session SFU channel** vs mandating **full WebRTC** on clients.  
2. TV default talk mode: **PTT-only**, or open-mic when a headset is connected?  
3. Persist local per-user mute/volume across reconnects in the same room, or session-only like chat block?  
4. Windows installer: **single branded `.exe` bootstrapper** OK, or must remain MSI with custom UI?  
5. Is the **web client** in 2.7.0 scope, or desktop + Android only?  
6. Which **TV devices** should remote-mic PTT be validated on first?  
7. Join/leave sounds: will you supply samples, or should the client generate simple original tones for approval?  
8. Should voice TX continue in **background** on phones, or pause when the app is not foreground?  
9. Any **macOS** voice expectation in 2.7.0 (desktop builds exist; shared plan names Windows/Linux/Android)?

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| TV remote mic silent / OEM-locked | Listen-only + headset; real-device matrix |
| Voice ducks or echoes against mpv | Separate streams; measure AEC; optional sidechain |
| Desktop WebRTC native weight | Prefer Opus SFU path |
| MSI → exe upgrade breaks updater | Explicit migration tests; keep UpgradeCode story |
| Play rejects RECORD_AUDIO on TV | `microphone` required=false; listen-only without permission |
| Scope creep (web, macOS, background TX) | Resolve open questions before build |

---

*End of planning document.*
