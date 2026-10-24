# YarmiplayTV 2.7.0 voice — build progress

**Do not tag or release until the owner confirms.**

## Approved decisions (locked in)

- Transport: Opus over our channel (UDP **9000**, TCP fallback); web uses the server's browser transport (`/yarmiplay/ws`). **No WebRTC client.**
- Align with `docs/VOICE_PROTOCOL.md` in YarmiplayServerTV PR #2 **after** that draft is revised away from WebRTC-only.
- TV talk: **tap toggle** (never hold-to-talk). Mic starts **off** on join. Focusable **Mic** button in player + room. Remote mic/voice keys toggle when delivered. Long-press OK is a shortcut only. Listen-only (no button) when no usable mic; headset preferred when connected.
- Per-person mute/volume **persist**.
- Phones: voice continues in the **background**; media notification gets voice controls (not TV).
- Web voice + **macOS** app/build with voice are in scope.
- Branded **.exe** Windows setup (non–MS Store).
- Softer join/leave tones (regenerated); mic-on/off cue sounds; client voice toggle **on by default**.
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

## Landed in this branch so far

- Version **2.7.0** bump (app, desktop, metainfo).
- Protocol types: `capabilities.voice`, `VoiceDescriptor`, signaling events/handlers.
- Settings: voice master toggle, talk mode (TV forced `TOGGLE`), gains, devices, join/leave sounds, persisted `voicePeerPrefs`, TV mic hint flags.
- `VoiceController` + Android stub `VoiceEngine` (capture probe + cue WAVs; Opus media pending server doc).
- TV UI: Mic button, mic-live badge + cue, one-time hint, listen-only banner, peer mute/volume steps, remote key toggle.
- Softer join/leave WAVs + mic on/off cues (`voice_join` / `voice_leave` / `voice_mic_on` / `voice_mic_off`).
- `RECORD_AUDIO` + optional `microphone` feature + FGS microphone permission.
- Privacy + Play data-safety notes.
- Unit tests: `VoiceProtocolTest`.

## Next (blocked / in progress)

1. Wait for revised server `VOICE_PROTOCOL.md` (Opus UDP/TCP + browser WS framing).
2. Real Opus media path on Android/desktop/macOS/web.
3. Phone notification voice actions + FGS mic type while transmitting.
4. Custom Windows `.exe` installer + updater path.
5. macOS dmg packaging + `NSMicrophoneUsageDescription`.

## What to test on real hardware (when a build is ready)

**Phone:** join a voice-enabled room; grant mic; open-mic + mute; background the app; notification mute/leave.

**TV:** listen-only without headset (no Mic button); with USB/BT headset — Mic button + tap toggle, mic-live badge + cue; try remote SEARCH / VOICE_ASSIST / ASSIST; confirm one-time hint once; mute persists after reconnect.

**Desktop:** device pickers, open-mic vs PTT, join/leave tones volume.

**Vanilla Syncplay:** no voice UI, no regressions.
