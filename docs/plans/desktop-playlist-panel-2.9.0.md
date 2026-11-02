# Desktop in-player playlist panel (v2.9.0)

**Scope:** Windows / Linux / macOS desktop Compose only (`DesktopRoomPanel` / `DesktopPlaylist`).  
**Out of scope:** phone, tablet, TV (`PlaylistPanel`), web (`PlaylistPanel.svelte`), and the shared room-screen `PlaylistContent` used in portrait / Room tab.  
**Not in scope:** search / filter UI (explicitly removed).

Wire format stays Syncplay’s `List<String>` filenames/URLs. Metadata (thumbs, duration, source) is **client-side enrichment** only and is never synced.

---

## Current pain points

1. **Weak “now playing” signal** — current row is only a slightly tinted background + play icon; easy to lose in a long list.
2. **Filename-only rows** — no duration, no thumbnail, no local vs Jellyfin/Plex/URL badge; users can’t tell what will resolve where.
3. **Discoverability** — double-click to play, Del to remove, Alt+↑↓ / drag to reorder are only in a footer hint; no right-click menu; no clear remove-selected control.
4. **Fixed 380 dp panel** — either crowds a narrow window or wastes space; always a hard overlay strip with no resize.
5. **Missing “Add current”** — mobile/TV have it; desktop only has file/folder/URL pickers.
6. **Empty playlist** — already present with confirm + undo; **keep it**.

What already works and must stay: Syncplay full-list replace via `SharedPlaylist` (add/remove/move/shuffle/sort/clear/undo), multi-select, external file DnD with insert line, Empty confirm (`playlist_empty` / `playlist_empty_confirm`), loop/shared options, save/load `.txt`.

---

## New layout

```
┌─ drag handle (resize) ─┬─ tabs: Room | Playlist | Chat | MAL ─ [✕] ─┐
│                        │ toolbar: +videos +folder +URLs +current     │
│                        │   shuffle · sort · undo · remove · Empty · ⋮│
│   video (unchanged)    │ ┌─────────────────────────────────────────┐ │
│                        │ │ [thumb] #  Now  title…     Local  24:10 │ │ ← current: accent bar + chip
│                        │ │ [thumb] #       title…     JF           │ │
│                        │ │ [thumb] #       title…     URL          │ │
│                        │ └─────────────────────────────────────────┘ │
│                        │ footer: N entries · selection · shortcuts   │
└────────────────────────┴─────────────────────────────────────────────┘
```

- Panel width: **default 400 dp**, drag left edge **300–640 dp** (session; video column keeps the remaining width).
- Rows: 16:9 thumb · index / play · title (+ path only when different) · source chip · duration · size-mismatch / missing icons.
- Current item: **3 dp accent leading bar**, stronger fill, “Now” chip, auto-scroll when `playlistIndex` changes.
- Empty state + Empty button unchanged in meaning (toolbar delete + confirm dialog).
- Hover highlight on rows; Remove selected uses a distinct icon from Empty.

---

## Interactions

| Action | How |
|---|---|
| Select | Click; Ctrl/Cmd multi; Shift range |
| Play | Double-click, Enter, or context **Play now** → `selectIndex` (room-synced) |
| Remove | Del/Backspace, toolbar Remove when selected, context **Remove** (multi if selection) → `shared.remove` |
| Reorder | Drag row, Alt+↑/↓, context Move up/down → `shared.move` / `shift` |
| Add | Videos / folder / URLs / **Add current** / external DnD |
| Empty | Existing Empty control + confirm → `shared.clear` (undo restores) |
| Context menu | Right-click row: Play now, Remove, Move up/down, Copy name |
| Keyboard | ↑↓ / Home / End / Enter / Del / Ctrl+A / Ctrl+Z / Alt+arrows |

Room-sync correctness: every structural edit still goes through `SharedPlaylist` / `SyncplayClient`. Enrichment never mutates the room list. Managed-room operator rules unchanged.

---

## Enrichment (local only)

For each entry string, resolve in order:

1. URL → source **URL**
2. Wait for local index, then `LocalMatcher` hit → **Local** (+ optional `VideoThumbnails`)
3. Peer `FileInfo` with matching name → duration hint
4. `MediaSource.findExact` → **Jellyfin** / **Plex** (+ `imageUrl`, duration)
5. Else **Unknown** / missing icon when `!isAvailable`

Do not mark rows unavailable until the local index is ready (avoids italic flash).

---

## Files

- Plan: this doc
- UI: `DesktopRoomPanel.kt` (resize shell) + `DesktopPlaylistPanel.kt` (list UI)
- Tests: duration / source label helpers; keep `RoomPanelUiTest` tags (`playlist_empty`, `playlist_list`, `playlist_row_*`)

## Non-goals

- Search / filter
- Changing Syncplay playlist wire format
- Phone / tablet / TV / web playlist chrome
- Floating undocked window (resize-in-place only for 2.9.0)
