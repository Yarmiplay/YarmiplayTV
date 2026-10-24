# Yarmiplay voice protocol (version 1)

Normative copy of YarmiplayServerTV `docs/VOICE_PROTOCOL.md` (PR #2 / 2.0.0).
YarmiplayTV 2.7.0 implements against this document.

See the server repository for the full text. Client-relevant rules:

- Native clients: 20 ms Opus frames (48 kHz mono) on UDP **9000** with AES-256-GCM (`yv1` packets), falling back to TLS over TCP **9000** (fingerprint-pinned, cert name `voice.local`).
- Browsers: the same sealed frames as binary WebSocket messages on `GET /yarmiplay/voice?t=<seat token>` on the Syncplay port.
- Signaling stays on the existing Syncplay / `/yarmiplay/ws` session (`voiceJoin`, `voiceJoined`, `voicePeers`, `voiceActivity`, `voiceLeave`, `voiceClosed`).
- No WebRTC, SDP, ICE, or `voiceSignal`.
