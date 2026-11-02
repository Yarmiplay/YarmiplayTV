# Yarmiplay voice protocol (version 1 → Syncplay connection mux)

Normative copy of YarmiplayServerTV `docs/VOICE_PROTOCOL.md` (PR #5 / 2.1.0).
YarmiplayTV 2.9.0 implements against that document.

Client-relevant rules:

- **Preferred (2.9.0 / server 2.1.0):** `voiceJoin` with `{version:1,mux:true}`; media on the **same**
  Syncplay connection — TCP/TLS records `0x1E` + BE u16 length (56..=1200) + one yv1 packet; browsers
  send the same yv1 packet as a **binary** WebSocket message on `/yarmiplay/ws`. Descriptor:
  `voice.mux = "syncplay"`, `voice.port` = Syncplay port. No separate voice port.
- **Legacy fallback (server 2.0.x):** omit `mux`; native UDP/TCP **9000** or `/yarmiplay/voice` WS.
- Signaling stays JSON lines on Syncplay / text side of `/yarmiplay/ws`.
- When voice is off: `voice: {enabled:false,message:"voice disabled on this server"}` — show that
  message as-is (distinct from an older server that never offered voice).
- No WebRTC, SDP, ICE, or `voiceSignal`.
