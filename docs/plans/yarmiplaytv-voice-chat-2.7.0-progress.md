# YarmiplayTV 2.7.0 voice — build progress

**Do not tag or release until the owner confirms.**

## Approved decisions (locked in)

- Transport: Opus over our channel (UDP **9000**, TCP fallback); web uses the server's browser transport (`/yarmiplay/ws`).
- Align with `docs/VOICE_PROTOCOL.md` in YarmiplayServerTV PR #2 **after** that draft is revised away from WebRTC-only.
- TV: **push-to-talk only**; remote mic when available, else listen-only.
- Per-person mute/volume **persist**.
- Phones: voice continues in the **background**; media notification gets voice controls (not TV).
- Web voice + **macOS** app/build with voice are in scope.
- Branded **.exe** Windows setup (non–MS Store).
- Generate join/leave tones (user approves); client voice toggle **on by default**.
- Privacy policy updated.

## Landed in this branch so far

- Version **2.7.0** bump (app, desktop, metainfo).
- Protocol types: `capabilities.voice`, `VoiceDescriptor`, signaling events/handlers (`voiceJoin` / `voiceJoined` / `voicePeers` / `voiceActivity` / `voiceClosed` / `voiceSignal`).
- Settings: voice master toggle, talk mode, gains, devices, join/leave sounds, **persisted** `voicePeerPrefs`.
- `VoiceController` + `VoiceEngine` interface (Null engine until platform media lands).
- Settings UI toggle (TV + mobile/desktop).
- `RECORD_AUDIO` + optional `microphone` feature + FGS microphone permission.
- Privacy + Play data-safety notes.
- Original join/leave WAVs (`voice_join` / `voice_leave`) for approval.
- Unit tests: `VoiceProtocolTest`.

## Next (blocked / in progress)

1. Wait for revised server `VOICE_PROTOCOL.md` (Opus UDP/TCP + browser WS framing).
2. Android `VoiceEngine`: capture (VOICE_COMMUNICATION / TV VOICE_RECOGNITION PTT), Opus, background + notification actions.
3. Desktop/macOS `VoiceEngine` (WASAPI / Pulse / CoreAudio) + mic usage string.
4. Web voice over session WS.
5. Room UI: speaking indicators, local mute/volume, PTT affordance, listen-only banner.
6. Custom Windows `.exe` installer + updater path.
7. macOS dmg packaging / signing report.

## What to test on real hardware (when a build is ready)

**Phone:** join a voice-enabled room; grant mic; open-mic + mute; background the app and confirm voice continues; use notification mute/PTT/leave.

**TV:** listen-only without headset; with USB/BT headset hold-to-talk; try remote mic PTT on your Google TV / Fire TV; confirm per-user mute persists after reconnect.

**Desktop:** device pickers, open-mic vs PTT, join/leave tones volume.

**Vanilla Syncplay:** no voice UI, no regressions.
