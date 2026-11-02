# Desktop in-player playlist panel (v2.9.0)

**Scope:** Windows / Linux / macOS desktop Compose only (`DesktopRoomPanel` / `DesktopPlaylist`).  
**Out of scope:** phone, tablet, TV (`PlaylistPanel`), web (`PlaylistPanel.svelte`), and the shared room-screen `PlaylistContent` used in portrait / Room tab.

Wire format stays Syncplay’s `List<String>` filenames/URLs. Metadata (thumbs, duration, source) is **client-side enrichment** only and is never synced.

---

## Current pain points

1. **Weak “now playing” signal** — current row is only a slightly tinted background + play icon; easy to lose in a long list.
2. **Filename-only rows** — no duration, no thumbnail, no local vs Jellyfin/Plex/URL badge; users can’t tell what will resolve where.
3. **Discoverability** — double-click to play, Del to remove, Alt+↑↓ / drag to reorder are only in a footer hint; no right-click menu; no per-row remove.
4. **No search** — large room playlists are hard to scan.
5. **Fixed 380 dp panel** — either crowds a narrow window or wastes space; always a hard overlay strip with no resize.
6. **Missing “Add current”** — mobile/TV have it; desktop only has file/folder/URL pickers.
7. **Empty playlist** — already present with confirm + undo; **keep it**.

What already works and must stay: Syncplay full-list replace via `SharedPlaylist` (add/remove/move/shuffle/sort/clear/undo), multi-select, external file DnD with insert line, Empty confirm (`playlist_empty` / `playlist_empty_confirm`), loop/shared options, save/load `.txt`.

---

## New layout

```
┌─ drag handle (resize) ─┬─ tabs: Room | Playlist | Chat | MAL ─ [✕] ─┐
│                        │ toolbar: +videos +folder +URLs +current     │
│                        │   shuffle · sort · undo · Empty · ⋮         │
│   video (unchanged)    │ search field (Ctrl+F)                       │
│                        │ ┌─────────────────────────────────────────┐ │
│                        │ │ [thumb] #  title…            Local  24:10│ │
│                        │ │ ║ NOW   filename / path hint   JF   ⚠   │ │ ← current: accent bar + chip
│                        │ │ [thumb] #  …                 URL        │ │
│                        │ └─────────────────────────────────────────┘ │
│                        │ footer: selection count · shortcuts hint    │
└────────────────────────┴─────────────────────────────────────────────┘
```

- Panel width: **default 400 dp**, drag left edge **300–640 dp** (session; not covering the video awkwardly — video column keeps the remaining width as today).
- Rows: 16:9 thumb (56 dp wide) · index / play · primary name (1–2 lines) · source chip · duration · size-mismatch / missing icons.
- Current item: **3 dp accent leading bar**, stronger fill, “Now” chip, auto-scroll into view when `playlistIndex` changes.
- Empty state + Empty button unchanged in meaning (toolbar delete + confirm dialog).

---

## Interactions

| Action | How |
|---|---|
| Select | Click; Ctrl/Cmd multi; Shift range |
| Play | Double-click, Enter, or context **Play now** → `selectIndex` (room-synced) |
| Remove | Del/Backspace, toolbar when selected, context **Remove** → `shared.remove` |
| Reorder | Drag row, Alt+↑/↓, context Move up/down → `shared.move` / `shift` (disabled while search filter active) |
| Add | Videos / folder / URLs / **Add current** / external DnD |
| Empty | Existing Empty control + confirm → `shared.clear` (undo restores) |
| Search | Filter box; case-insensitive substring on entry string; Escape clears |
| Context menu | Right-click row: Play now, Remove, Move up/down, Copy name |
| Keyboard | Existing ↑↓/Enter/Del/Ctrl+A/Z/Alt+arrows; **Ctrl+F** focuses search |

Room-sync correctness: every structural edit still goes through `SharedPlaylist` / `SyncplayClient` (`setPlaylist` / index select). Enrichment never mutates the room list. Managed-room operator rules unchanged (server / client already gate non-operators).

---

## Enrichment (local only)

For each entry string, resolve in order:

1. URL → source **URL**
2. `LocalMatcher` hit → **Local** (+ optional `VideoThumbnails` file)
3. Peer `FileInfo` with matching name → duration/size hint
4. `MediaSource.findExact` → **Jellyfin** / **Plex** (+ `imageUrl`, duration)
5. Else **Unknown** / show missing icon when `!isAvailable`

Cache by filename for the session; do not block the first paint.

---

## Files

- Plan: this doc
- UI: `shared/.../desktopMain/.../DesktopRoomPanel.kt` (resize shell) + `DesktopPlaylistPanel.kt` (list UI)
- Tests: extend / add desktop unit coverage for filter index mapping + duration format; keep `RoomPanelUiTest` tags (`playlist_empty`, `playlist_list`, `playlist_row_*`)

## Non-goals

- Changing Syncplay playlist wire format
- Phone / tablet / TV / web playlist chrome
- Floating undocked window (resize-in-place only for 2.9.0)
