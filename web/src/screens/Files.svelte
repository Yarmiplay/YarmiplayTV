<script lang="ts">
  import { go, library, session as sessionStore } from "../app/context";
  import Icon from "../components/Icon.svelte";
  import { keepsFolders, type LocalFile } from "../lib/local/library";
  import { thumbnail } from "../lib/local/thumbnails";

  /** Files on this device (MobileLocal.kt): the media folders' videos, and opening one, here or for the room. */
  const session = $derived($sessionStore);
  let files = $state<LocalFile[]>(library.files);
  let filter = $state("");
  let connected = $state(false);
  let thumbs = $state<Record<string, string | null>>({});

  $effect(() => library.subscribe(() => (files = [...library.files])));
  $effect(() => {
    if (!session) return;
    return session.room.subscribe((r) => (connected = r.status === "CONNECTED"));
  });

  const shown = $derived(
    files.filter((f) => !filter.trim() || `${f.directory} ${f.name}`.toLowerCase().includes(filter.trim().toLowerCase())),
  );

  /** Loads a thumbnail once the tile scrolls into view. */
  function lazyThumb(node: HTMLElement, file: LocalFile) {
    const key = `${file.folder}/${file.directory}/${file.name}`;
    const observer = new IntersectionObserver((entries) => {
      if (!entries.some((e) => e.isIntersecting)) return;
      observer.disconnect();
      void thumbnail(file).then((url) => (thumbs = { ...thumbs, [key]: url }));
    });
    observer.observe(node);
    return { destroy: () => observer.disconnect() };
  }

  async function play(file: LocalFile, inRoom: boolean) {
    await session?.playLocal(await file.open(), inRoom);
    go("#/player");
  }

  async function add(file: LocalFile) {
    session?.addLocalFiles([await file.open()]);
  }

  function open(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    (event.target as HTMLInputElement).value = "";
    if (file && session) {
      void session.playLocal(file, connected);
      go("#/player");
    }
  }
</script>

<main class="files">
  <header>
    <h1>Files on this device</h1>
    <div class="row">
      <label class="button">
        <Icon name="video_file" size={20} />Open a video
        <input type="file" accept="video/*,.mkv,.avi,.ts,.m2ts,.webm" onchange={open} hidden />
      </label>
      <a class="button quiet" href="#/settings/media"><Icon name="folder" size={20} />Media folders</a>
    </div>
  </header>

  {#if files.length === 0}
    <section class="card">
      <p>No videos yet. Add a media folder: videos in it are found by themselves when a room plays them.</p>
      {#if !keepsFolders()}<p class="dim small">This browser asks for the folder again on each visit; Chrome and Edge remember it.</p>{/if}
    </section>
  {:else}
    <input class="filter" placeholder="Filter" bind:value={filter} aria-label="Filter files" />
    <ul class="grid">
      {#each shown as f (`${f.folder}/${f.directory}/${f.name}`)}
        {@const key = `${f.folder}/${f.directory}/${f.name}`}
        <li class="card tile" use:lazyThumb={f}>
          <div class="thumb">
            {#if thumbs[key]}<img src={thumbs[key]} alt="" />{/if}
          </div>
          <b class="name">{f.name}</b>
          <span class="dim small">{f.directory || f.folder}</span>
          <div class="row">
            {#if connected}
              <button class="button small" onclick={() => play(f, true)}><Icon name="play_arrow" size={18} />Play in the room</button>
              <button class="button quiet small" onclick={() => add(f)}><Icon name="playlist_add" size={18} />Add to playlist</button>
            {/if}
            <button class="button quiet small" onclick={() => play(f, false)}><Icon name="play_circle" size={18} />Play here</button>
          </div>
        </li>
      {/each}
    </ul>
  {/if}
</main>

<style>
  .files {
    max-width: 1100px;
    margin: 0 auto;
    padding: 16px;
    display: grid;
    gap: 16px;
  }
  header {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
  }
  h1 {
    margin: 0;
    font-size: 24px;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .filter {
    background: var(--surface-high);
    border: 0;
    border-radius: 10px;
    padding: 10px 12px;
  }
  .grid {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 12px;
  }
  .tile {
    display: grid;
    gap: 6px;
    align-content: start;
  }
  .thumb {
    aspect-ratio: 16 / 9;
    border-radius: 10px;
    background: var(--surface-high);
    overflow: hidden;
  }
  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  .name {
    overflow-wrap: anywhere;
  }
  .small {
    font-size: 13px;
  }
  .button.small {
    padding: 6px 12px;
    font-size: 14px;
  }
</style>
