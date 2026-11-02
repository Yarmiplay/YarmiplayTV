# Yarmiplay voice protocol (version 1 → Syncplay-port media)

Normative copy of YarmiplayServerTV `docs/VOICE_PROTOCOL.md` (PR #2 / 2.0.0), plus the
**Syncplay-port media** plan for YarmiplayServerTV 2.1.0 / YarmiplayTV 2.9.0
(`docs/plans/voice-syncplay-port-mux-2.9.0.md`).

See the server repository for the full text. Client-relevant rules:

- **Preferred (2.9.0 / voice.version 2):** all platforms send the same sealed `yv1` frames as binary
  WebSocket messages on `GET /yarmiplay/voice?t=<seat token>` on the **Syncplay port** (no UDP/TCP 9000).
- **Legacy (server 2.0.x):** native clients: 20 ms Opus (48 kHz mono) on UDP **9000** with AES-256-GCM,
  falling back to TLS over TCP **9000** (fingerprint-pinned, cert name `voice.local`); browsers already
  used the Syncplay-port WebSocket.
- Signaling stays on the existing Syncplay / `/yarmiplay/ws` session (`voiceJoin`, `voiceJoined`,
  `voicePeers`, `voiceActivity`, `voiceLeave`, `voiceClosed`).
- When the host turns voice off, `capabilities.voiceDisabled` is true and the client shows
  **Voice disabled on this server** (distinct from an older server that never offered voice).
- No WebRTC, SDP, ICE, or `voiceSignal`.
