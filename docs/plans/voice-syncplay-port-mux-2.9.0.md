# Plan: voice on the Syncplay port (no separate 9000)

**Status:** awaiting owner confirmation before any YarmiplayServerTV / NoireServerTV edits.  
**Client prep (YarmiplayTV 2.9.0):** allowed to proceed in parallel.

## Problem

YarmiplayServerTV 2.0+ binds voice on **UDP+TCP 9000** (hardcoded `voice::PORT`). Browsers already send the same `yv1` frames on the **Syncplay port** via `GET /yarmiplay/voice`. Native clients use 9000 first.

If two servers share a host (YarmiplayServerTV + NoireServerTV), or anything else holds 9000:

- Syncplay (default **8999**) may still start on the second app (or fail loudly with UI error).
- Voice bind failure is only a **warn**: `voice relay failed to listen; voice stays off`.
- Because `voice_offered()` requires a listening hub, **capabilities.voice becomes false** — including the Syncplay-port WebSocket path that never needed 9000.
- Clients then get no remote audio / no speaking overlay (heard-PCM based), while local mic rings still work once a seat somehow connects — or voice UI hides entirely.

## Goal

Relay `yv1` Opus through the **Syncplay port only** (default 8999). Drop the hard dependency on a second port. Keep packet crypto/framing. Loud logs. Backward-compatible fallback where cheap.

## Recommended design (not same-socket JSON multiplex)

**Do not** interleave binary frames on the Syncplay JSON line reader. That forces a demux rewrite and can stall room/chat under media load (why 9000 existed).

**Do** use the **already-shipped Syncplay-port media path**:

| Path | Role |
|---|---|
| Syncplay TCP / TLS / `/yarmiplay/ws` | Signaling only (`voiceJoin`, peers, activity) — unchanged |
| `GET /yarmiplay/voice?t=<seat token>` on **Syncplay port** | Media: one binary WebSocket message = one §6.1 `yv1` packet |

Native desktop/Android join the same WebSocket browsers already use. Packet bytes, AES-GCM, Opus 20 ms / 48 kHz mono stay identical.

Optional later: raw length-prefixed TCP upgrade on the Syncplay mux (same port, no WS). Not required for v1 of this change if OkHttp/Java-HTTP WS works on all native targets.

### Descriptor / capability shape (proposal)

Keep extension protocol **1**. Keep `yv1` packet **version byte 1**.

Bump **`voice.version` → 2** so old clients that only understand “must use udpPort/tcpPort 9000” get a clear `voiceClosed` / can fall back when the server still offers legacy.

```json
"voice": {
  "version": 2,
  "codec": "opus",
  "clockRate": 48000,
  "channels": 1,
  "ptime": 20,
  "maxBitrate": 32000,
  "fec": true,
  "dtx": true,
  "transport": "yv1-syncplay",
  "wsPath": "/yarmiplay/voice",
  "maxParticipants": 8,
  "fingerprint": ""
}
```

- **`transport`: `yv1-syncplay`** — media on Syncplay host+port via `wsPath` (ws/wss matching the session’s TLS).
- **`udpPort` / `tcpPort` omitted** (or `0`) when the legacy 9000 listener is not up.
- **`fingerprint` empty** when there is no separate voice TLS cert (WS rides the Syncplay TLS / cleartext like `/yarmiplay/ws`).

Legacy servers (2.0.0–2.0.2): still advertise `version: 1`, `transport: "yv1"`, ports 9000, fingerprint set. New clients try Syncplay-port WS first when `transport` is `yv1-syncplay` or when ports are 0/missing; else keep today’s UDP→TCP 9000 fallback.

### Server behavior (YarmiplayServerTV first)

1. **Create `VoiceHub` without binding 9000.** Attach it whenever Syncplay is running and voice toggle is on. `voice_offered()` = hub present + toggle + not vanilla (no “listening on 9000” requirement).
2. **Default: do not bind UDP/TCP 9000.** UPnP maps only Syncplay TCP (voice rides it). Remove voice UPnP entries for 9000 when legacy is off.
3. **Optional legacy listener** (compat flag, default off in 2.1.0 or on for one minor): try `VoiceRuntime::bind(9000,9000)`; on `AddrInUse` / any error → **error!-level** log + Dashboard/Syncplay status string; voice **stays on** via Syncplay-port WS.
4. **Loud logs (exact strings to add/keep):**
   - success: `voice hub ready (syncplay-port media path=/yarmiplay/voice)`
   - legacy success: `voice relay listening udp=9000 tcp=9000` (existing)
   - legacy fail: upgrade today’s warn to **error** and surface in UI:  
     `Voice legacy ports 9000 busy ({e}); Syncplay-port voice still available`
   - Syncplay fail (unchanged, already UI): `Port {port} is already in use by another program`
5. **ingest/forward** unchanged: WS `serve_ws` already re-seals per seat. No second crypto.

### Client behavior (YarmiplayTV 2.9.0 — all platforms)

| Platform | Media |
|---|---|
| Web | Already `/yarmiplay/voice` — only adapt to `voice.version` 2 / missing ports |
| Desktop (Win/Linux/macOS) | New WS transport to Syncplay host:port; stop requiring 9000 when server is v2 |
| Android phone/tablet/TV | Same shared `voice-media` / engine path |

Priority order when connecting after `voiceJoined`:

1. If descriptor/joined says Syncplay-port (`transport=yv1-syncplay` or ports absent/0): open WS to `ws[s]://host:syncplayPort/wsPath?t=token`.
2. Else legacy: UDP 9000 → TCP 9000 fingerprint TLS (current).
3. On media connect failure: status line **not** silent — e.g. `Couldn't open voice on the Syncplay port` / `Voice ports blocked`; retry with backoff; show “server has no voice” when `!capabilities.voice`.

Also ship: **RX diagnostic** (“Receiving audio from X” + level) and a clear banner when the session is Yarmiplay but `capabilities.voice` is false (old Noire / bind-all-or-nothing).

### Bandwidth / latency

| | Today (UDP 9000) | Syncplay-port WS |
|---|---|---|
| Payload | ~32 kbit/s Opus + ~56 B header/tag per 20 ms ≈ **~55–70 kbit/s** one talker | Same Opus + WS framing (~2–8 B/frame) + TCP/TLS |
| Extra | — | TCP ACKs; optional TLS if Syncplay is TLS (~1–2% typically) |
| Latency | ~20–60 ms jitter buffer already | +0–20 ms typical vs UDP on LAN; WAN similar to Syncplay RTT |
| Head-of-line | Isolated from Syncplay JSON | Media on **second connection** to same port → Syncplay JSON not blocked |
| N talkers | N × bitrate forward from server | Same |

Room of 4 with 2 speaking ≈ 110–140 kbit/s aggregate through the host — fine next to file relay.

## Version numbers

| Repo | Version | Why |
|---|---|---|
| **YarmiplayServerTV** | **2.1.0** | Voice no longer depends on 9000; `voice.version` 2 / `yv1-syncplay`; loud bind errors. Patch 2.0.3 only if you prefer a smaller label — recommend **2.1.0**. |
| **NoireServerTV** | **next Noire release after merging YarmiplayServerTV 2.1.0** (e.g. 1.6.6 / 1.7.0 — owner’s scheme). Must get the hub-without-9000 fix, not only a partial merge of 2.0.0. |
| **YarmiplayTV** | **2.9.0** | Native Syncplay-port media + legacy fallback + unsupported-server / RX diagnostics. |
| **voice.version** | **2** | Advertises Syncplay-port media; packet magic/version byte stays 1. |
| Extension `features.yarmiplay.protocol` | **1** | Unchanged. |

## Rollout order

1. Confirm this plan.
2. **YarmiplayServerTV 2.1.0** (hub without 9000, advertise v2, loud logs, optional legacy 9000).
3. **YarmiplayTV 2.9.0** client (native WS path + fallback + banners) — prep can start now.
4. **NoireServerTV** merge/port of 2.1.0 voice changes; release from private pipeline.
5. After clients are out: consider defaulting legacy 9000 off entirely in a later server minor.

## Out of scope / non-goals

- No WebRTC.
- No mixing on the server.
- No change to seat tokens/keys or Opus bitrate defaults.
- No edits to YarmiplayServerTV or NoireServerTV until you confirm.

## Open choices for you

1. **Legacy 9000 default in 2.1.0:** off (clean) vs on-if-free (max compat with 2.7–2.8.x clients still on UDP)? Recommendation: **try-bind optional, default off** once 2.9.0 is required for voice on new servers; or **on-if-free for one minor** then off.
2. **Native media:** WebSocket only (match browser) vs also raw length-prefixed TCP on the mux? Recommendation: **WebSocket only** for 2.9.0.
3. **Same TCP connection multiplex** instead of second connection to same port? Recommendation: **no** — keep separate WS connection on the Syncplay port.
