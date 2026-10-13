<script lang="ts">
  import { session as sessionStore } from "../app/context";
  import type { RoomState } from "../lib/syncplay/client";
  import { followsOperators } from "../lib/syncplay/client";
  import * as Filenames from "../lib/syncplay/filenames";

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

  $effect(() => {
    if (!session) return;
    const stops = [session.room.subscribe((r) => (room = r)), session.canUndo.subscribe((u) => (canUndo = u))];
    return () => stops.forEach((s) => s());
  });

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
        Add files
        <input type="file" multiple accept="video/*,.mkv,.avi,.ts,.m2ts,.webm" onchange={addFiles} hidden disabled={locked} />
      </label>
      <button class="button quiet small" onclick={() => session?.sortAlphabetically()} disabled={locked || playlist.length < 2}>Sort A to Z</button>
      <button class="button quiet small" onclick={() => session?.undo()} disabled={locked || !canUndo}>Undo</button>
      <button class="button quiet small" onclick={() => (menu = !menu)} disabled={locked} aria-expanded={menu}>More</button>
    </div>
    {#if menu}
      <div class="actions">
        <button class="button quiet small" onclick={() => (session?.shuffle(false), (menu = false))}>Shuffle remaining</button>
        <button class="button quiet small" onclick={() => (session?.shuffle(true), (menu = false))}>Shuffle entire</button>
        <button
          class="button quiet small"
          disabled={!selected.size}
          onclick={() => {
            session?.remove(selected);
            selected = new Set();
            menu = false;
          }}>Remove selected</button
        >
      </div>
    {/if}
    <form class="link" onsubmit={addLink}>
      <input type="url" placeholder="Add a link" bind:value={link} aria-label="Link to add" disabled={locked} />
      <button class="button quiet small" type="submit" disabled={locked || !link.trim()}>Add</button>
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
            <button class="play" onclick={() => session?.selectIndex(i)} disabled={locked} aria-label="Play for everyone">▶</button>
          {:else}
            <span class="play now" aria-label="Playing">●</span>
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
    background: none;
    border: 0;
    padding: 8px 10px;
    cursor: pointer;
    color: var(--text-dim);
  }
  .play.now {
    color: var(--accent);
  }
</style>
