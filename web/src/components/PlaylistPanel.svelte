<script lang="ts">
  import { session as sessionStore } from "../app/context";
  import type { RoomState } from "../lib/syncplay/client";
  import { followsOperators } from "../lib/syncplay/client";
  import * as Filenames from "../lib/syncplay/filenames";
  import { deleteSavedPlaylist, savePlaylist, savedPlaylistsStore, type SavedPlaylist } from "../lib/data/savedPlaylists";
  import Icon from "./Icon.svelte";

  /**
   * The shared playlist (MobilePanels.kt's playlist and DesktopRoomPanel.kt): select an entry for everyone, add files
   * or links, drag to reorder, select several (Ctrl/Shift), remove with Delete, Sort A to Z, shuffle, and Undo.
   */
  const session = $derived($sessionStore);
  let room = $state<RoomState | null>(null);
  let canUndo = $state(false);
  let selected = $state(new Set<number>());
  let anchor = $state<number | null>(null);
  let dragFrom = $state<number | null>(null);
  let link = $state("");
  let menu = $state(false);
  let savedPlaylists = $state<SavedPlaylist[]>([]);
  let showSaved = $state(false);
  let savePrompt = $state(false);
  let playlistNameInput = $state("");

  $effect(() => {
    if (!session) return;
    const stops = [
      session.room.subscribe((r) => (room = r)),
      session.canUndo.subscribe((u) => (canUndo = u)),
      savedPlaylistsStore.subscribe((list) => (savedPlaylists = list)),
    ];
    return () => stops.forEach((s) => s());
  });

  function handleSaveCurrent() {
    if (!playlist.length) return;
    playlistNameInput = room?.room ? `Room ${room.room}` : "My Playlist";
    savePrompt = true;
  }

  function confirmSave() {
    if (playlistNameInput.trim() && playlist.length) {
      savePlaylist(playlistNameInput.trim(), playlist);
      savePrompt = false;
      showSaved = true;
    }
  }

  function loadSaved(sp: SavedPlaylist, mode: "add" | "replace") {
    if (!session) return;
    if (mode === "add") {
      session.addEntries(sp.entries);
    } else {
      session.replacePlaylist(sp.entries);
    }
  }

  const playlist = $derived(room?.playlist ?? []);
  const connected = $derived(room?.status === "CONNECTED");
  const locked = $derived(room ? followsOperators(room) : false);

  function click(i: number, event: MouseEvent) {
    if (event.shiftKey && anchor !== null) {
      const [a, b] = [Math.min(anchor, i), Math.max(anchor, i)];
      selected = new Set(Array.from({ length: b - a + 1 }, (_, k) => a + k));
    } else if (event.ctrlKey || event.metaKey) {
      const next = new Set(selected);
      if (next.has(i)) next.delete(i);
      else next.add(i);
      selected = next;
      anchor = i;
    } else {
      selected = new Set([i]);
      anchor = i;
    }
  }

  function keydown(event: KeyboardEvent) {
    if (!session || locked) return;
    if (event.key === "Delete" && selected.size) {
      session.remove(selected);
      selected = new Set();
      event.preventDefault();
      event.stopPropagation();
    } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
      session.undo();
      event.preventDefault();
      event.stopPropagation();
    }
  }

  function addFiles(event: Event) {
    const input = event.target as HTMLInputElement;
    const files = [...(input.files ?? [])];
    input.value = "";
    if (files.length) session?.addLocalFiles(files);
  }

  function addLink(event: SubmitEvent) {
    event.preventDefault();
    if (session && session.addUrls(link.split(/\s+/)) > 0) link = "";
  }
</script>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<div class="playlist" tabindex="0" onkeydown={keydown} role="listbox" aria-label="Shared playlist" aria-multiselectable="true">
  {#if !connected}
    <p class="dim pad">Join a room to share a playlist.</p>
  {:else}
    <div class="actions">
      <label class="button quiet small" class:disabled={locked}>
        <Icon name="playlist_add" size={18} />Add files
        <input type="file" multiple accept="video/*,audio/*,.mkv,.avi,.ts,.m2ts,.webm,.mp3,.flac,.aac,.m4a,.ogg,.opus,.wav" onchange={addFiles} hidden disabled={locked} />
      </label>
      <button class="button quiet small" onclick={() => session?.sortAlphabetically()} disabled={locked || playlist.length < 2}><Icon name="sort_by_alpha" size={18} />Sort A to Z</button>
      <button class="button quiet small" onclick={handleSaveCurrent} disabled={playlist.length === 0}><Icon name="bookmarks" size={18} />Save</button>
      <button class="button quiet small" onclick={() => (showSaved = !showSaved)}><Icon name="queue_music" size={18} />Saved ({savedPlaylists.length})</button>
      <button class="button quiet small" onclick={() => session?.undo()} disabled={locked || !canUndo}><Icon name="undo" size={18} />Undo</button>
      <button class="button quiet small" onclick={() => (menu = !menu)} disabled={locked} aria-expanded={menu}><Icon name="more_vert" size={18} />More</button>
    </div>
    {#if menu}
      <div class="actions">
        <button class="button quiet small" onclick={() => (session?.shuffle(false), (menu = false))}><Icon name="shuffle" size={18} />Shuffle remaining</button>
        <button class="button quiet small" onclick={() => (session?.shuffle(true), (menu = false))}><Icon name="shuffle" size={18} />Shuffle entire</button>
        <button
          class="button quiet small"
          disabled={!selected.size}
          onclick={() => {
            session?.remove(selected);
            selected = new Set();
            menu = false;
          }}><Icon name="delete" size={18} />Remove selected</button
        >
      </div>
    {/if}
    {#if savePrompt}
      <form class="link" onsubmit={(e) => { e.preventDefault(); confirmSave(); }}>
        <input type="text" placeholder="Playlist name" bind:value={playlistNameInput} aria-label="Playlist name" />
        <button class="button small" type="submit" disabled={!playlistNameInput.trim()}><Icon name="check" size={18} />Save</button>
        <button class="button quiet small" type="button" onclick={() => (savePrompt = false)}>Cancel</button>
      </form>
    {/if}
    {#if showSaved}
      <div class="saved-box">
        <div class="saved-header">
          <b>Saved Playlists</b>
          <button class="button quiet small" onclick={() => (showSaved = false)} aria-label="Close saved playlists"><Icon name="close" size={16} /></button>
        </div>
        {#if savedPlaylists.length === 0}
          <p class="dim small pad">No saved playlists yet.</p>
        {:else}
          <ul class="saved-items">
            {#each savedPlaylists as sp (sp.id)}
              <li class="saved-row">
                <div class="saved-info">
                  <span class="name">{sp.name}</span>
                  <span class="dim small">{sp.entries.length} {sp.entries.length === 1 ? 'item' : 'items'}</span>
                </div>
                <div class="saved-actions">
                  <button class="button quiet small" onclick={() => loadSaved(sp, "add")} disabled={locked} title="Add to current playlist"><Icon name="playlist_add" size={16} />Add</button>
                  <button class="button quiet small" onclick={() => loadSaved(sp, "replace")} disabled={locked} title="Replace current playlist"><Icon name="play_arrow" size={16} />Replace</button>
                  <button class="button quiet small icon-btn" onclick={() => deleteSavedPlaylist(sp.id)} title="Delete saved playlist" aria-label="Delete saved playlist"><Icon name="delete" size={16} /></button>
                </div>
              </li>
            {/each}
          </ul>
        {/if}
      </div>
    {/if}
    <form class="link" onsubmit={addLink}>
      <input type="url" placeholder="Add a link" bind:value={link} aria-label="Link to add" disabled={locked} />
      <button class="button quiet small" type="submit" disabled={locked || !link.trim()}><Icon name="add" size={18} />Add</button>
    </form>
    {#if playlist.length === 0}
      <p class="dim pad">The playlist is empty. Add videos from this device or a link; everyone in the room sees the same list.</p>
    {/if}
    <ol>
      {#each playlist as entry, i (i + entry)}
        <li
          class:current={room?.playlistIndex === i}
          class:selected={selected.has(i)}
          class:over={dragFrom !== null && dragFrom !== i}
          draggable={!locked}
          ondragstart={() => (dragFrom = i)}
          ondragover={(e) => e.preventDefault()}
          ondrop={(e) => {
            e.preventDefault();
            if (dragFrom !== null && dragFrom !== i) session?.move(dragFrom, i);
            dragFrom = null;
          }}
          ondragend={() => (dragFrom = null)}
          role="option"
          aria-selected={selected.has(i)}
        >
          <button class="entry" onclick={(e) => click(i, e)} ondblclick={() => !locked && session?.selectIndex(i)}>
            <span class="name">{Filenames.isUrl(entry) ? entry : Filenames.baseName(entry)}</span>
            {#if session && !session.isAvailable(entry)}
              <span class="dim tag">not on this device</span>
            {/if}
          </button>
          {#if room?.playlistIndex !== i}
            <button class="play" onclick={() => session?.selectIndex(i)} disabled={locked} aria-label="Play for everyone" title="Play for everyone"><Icon name="play_arrow" size={20} /></button>
          {:else}
            <span class="play now" role="img" aria-label="Playing" title="Playing"><Icon name="play_circle" size={20} /></span>
          {/if}
        </li>
      {/each}
    </ol>
  {/if}
</div>

<style>
  .playlist {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-height: 0;
    flex: 1;
    outline: none;
  }
  .pad {
    padding: 0 16px;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    padding: 0 12px;
  }
  .small {
    padding: 6px 12px;
    font-size: 14px;
  }
  .disabled {
    opacity: 0.5;
    pointer-events: none;
  }
  .link {
    display: flex;
    gap: 6px;
    padding: 0 12px;
  }
  .link input {
    flex: 1;
    min-width: 0;
    background: var(--surface-high);
    border: 0;
    border-radius: 8px;
    padding: 6px 10px;
  }
  ol {
    list-style: none;
    margin: 0;
    padding: 0 8px 8px;
    overflow: auto;
    flex: 1;
  }
  li {
    display: flex;
    align-items: center;
    border-radius: 10px;
  }
  li.selected {
    background: var(--surface-high);
  }
  li.current .name {
    color: var(--accent);
    font-weight: 600;
  }
  li.over {
    outline: 1px dashed var(--text-dim);
  }
  .entry {
    flex: 1;
    min-width: 0;
    text-align: left;
    background: none;
    border: 0;
    padding: 8px 10px;
    cursor: pointer;
    display: grid;
  }
  .name {
    overflow-wrap: anywhere;
  }
  .tag {
    font-size: 12px;
  }
  .play {
    display: inline-flex;
    align-items: center;
    background: none;
    border: 0;
    padding: 8px 10px;
    cursor: pointer;
    color: var(--text-dim);
  }
  .play.now {
    color: var(--accent);
  }
  .saved-box {
    display: flex;
    flex-direction: column;
    gap: 8px;
    background: var(--surface-high);
    border-radius: 10px;
    padding: 8px 12px;
  }
  .saved-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .saved-items {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
    max-height: 200px;
    overflow-y: auto;
  }
  .saved-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 6px 8px;
    border-radius: 6px;
    background: var(--surface);
  }
  .saved-info {
    display: flex;
    flex-direction: column;
    min-width: 0;
    flex: 1;
  }
  .saved-actions {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .icon-btn {
    padding: 4px 6px;
  }
</style>
