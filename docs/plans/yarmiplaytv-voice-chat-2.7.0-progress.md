# YarmiplayTV 2.7.0 voice — build progress

**Release gate (owner-approved sequencing):** merge and tag **v2.7.0** once backward-compat (1.9.6 + vanilla Syncplay) passes and CI is green — do **not** wait for server 2.0.0. If compat fails, fix first; do not release.

## Approved decisions (locked in)

- Transport: Opus `yv1` on UDP **9000** with AES-256-GCM, TLS TCP **9000** fallback; browsers use `GET /yarmiplay/voice?t=<seat token>` on the Syncplay port. **No WebRTC client.**
- Align with server `docs/VOICE_PROTOCOL.md` (YarmiplayServerTV PR #2 / 2.0.0) — final.
- TV talk: **tap toggle** (never hold-to-talk). Mic starts **off** on join. Focusable **Mic** button in player + room. Remote mic/voice keys toggle when delivered. Long-press OK is a shortcut only. Listen-only (no button) when no usable mic; headset preferred when connected.
- Per-person mute/volume **persist**.
- Phones: voice continues in the **background**; media notification gets voice controls (not TV).
- Web voice + **macOS** app/build with voice are in scope.
- Branded **.exe** Windows setup (non–MS Store).
- Join/leave tones (**approved final** softer `voice_join` / `voice_leave`); mic-on/off cue sounds; client voice toggle **on by default**.
- Privacy policy updated.

## TV mic keys (for real-device testing)

Handled when the app receives them (`TvVoiceKeys` / `MainActivity.dispatchKeyEvent`):

| Key | Code | Notes |
|---|---|---|
| `KEYCODE_SEARCH` | 84 | Common mic / Assistant button on many Android TV remotes |
| `KEYCODE_VOICE_ASSIST` | 231 | Voice-assist key |
| `KEYCODE_ASSIST` | 219 | Assist key on some remotes |
| `KEYCODE_MEDIA_RECORD` | 130 | Record key on a few remotes |

**Every remote:** focusable **Mic** button in player controls and room panel — press OK to toggle.

**Shortcut only:** long-press OK / Center / Enter (≥500 ms) while player controls are hidden.

If the system opens Assistant instead of delivering a key, use the on-screen Mic button.

## Landed in this branch

- Version **2.7.0** bump (app, desktop, metainfo).
- Protocol: `capabilities.voice`, `VoiceDescriptor` (`yv1`, `wsPath`, `fingerprint`), `voiceJoined` secrets, closed reasons, `voiceLeave: {}`.
- Settings: voice master toggle, talk mode (TV forced `TOGGLE`), gains, devices, join/leave sounds, persisted `voicePeerPrefs`, TV mic hint flags.
- `VoiceController` + platform engines on real media path.
- **`:voice-media`**: Concentus Opus, AES-256-GCM `yv1` packets, UDP + fingerprint-pinned TLS TCP fallback, keepalives.
- **Android / desktop / macOS**: `AndroidVoiceEngine` / `DesktopVoiceEngine` capture + playback through `VoiceMediaSession`.
- **Web**: `WebVoiceEngine` on `/yarmiplay/voice` (WebCrypto + WebCodecs Opus) + `VoiceController` on `RoomSession`.
- TV UI: Mic button, mic-live badge + cue, one-time hint, listen-only banner, peer mute/volume, remote key toggle.
- Phone/tablet: room speaking/mute/volume, notification voice actions, FGS `mediaPlayback|microphone`.
- Join/leave WAVs **locked** + mic on/off cues.
- `RECORD_AUDIO` + optional `microphone` feature + FGS microphone permission.
- Windows: `TargetFormat.Exe`, `WindowsSetupUi`, `WindowsUpdater` `.exe` path, `packaging/windows/README`.
- macOS: `NSMicrophoneUsageDescription` in jpackage `infoPlist`.
- Privacy + Play data-safety notes.
- Unit tests: `VoiceProtocolTest`, `Yv1PacketTest`, `TvVoiceKeysTest`, web `yv1.test.ts`.

## Merge / release gate

- E2E against live YarmiplayServerTV voice passed (UDP/TCP, mute/volume, speaking, 9th seat, toggles, vanilla, room change, device pickers).
- Backward-compat E2E (`BackwardCompatE2ETest`): 1.9.6 + vanilla Syncplay — no voice UI, no port-9000 traffic; sync/chat/playlist OK.
- CI green on the PR branch; release.yml attaches Windows, **macOS .dmg**, and Linux; pages.yml prefers release `.dmg`.
- Owner approved: merge PR #8 and release **v2.7.0** without waiting for server 2.0.0 once this gate clears.

## Still open (not blocking merge)

1. Custom bootstrapper fat-binary that hosts `WindowsSetupUi` for silent installs (2.7.1 ships jpackage Exe as `YarmiplayTV-*-setup.exe` meanwhile).
2. Polished web room mic/peer UI (engine + controller are wired; chrome can follow).

## What to test on real hardware (debug / sideload build — no release tag)

**Phone / tablet:** join a voice-enabled YarmiplayServerTV 2.0 room; grant mic; open-mic + mute; background the app; notification mute/leave; hear peers (Opus path live).

**TV:** listen-only without headset (no Mic button); with USB/BT headset — Mic tap toggle, mic-live badge + cue; try remote SEARCH / VOICE_ASSIST / ASSIST; one-time hint once; mute persists after reconnect; hear peers.

**Desktop / macOS:** join voice room; device capture; open-mic vs PTT; join/leave tones; UDP then TCP fallback if UDP is blocked.

**Web:** join from `tv.yarmiplay.com/watch/` against a voice-on server; allow mic; talk over `/yarmiplay/voice`.

**Vanilla Syncplay:** no voice UI, no regressions.
