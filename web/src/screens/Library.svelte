<script lang="ts">
  import { route } from "../app/context";
  import { sourceByKey, sources } from "../app/media";
  import MediaTile, { knownItems } from "../components/MediaTile.svelte";
  import type { MediaItem, MediaSource } from "../lib/media";

  /**
   * Browsing the media servers (MobileBrowse.kt): every server's libraries side by side, then a library's shows and
   * films, a show's seasons and episodes. `#/library/<server>/<item>` opens one.
   */
  const parts = $derived($route.split("/").slice(2).map(decodeURIComponent));
  const source = $derived(parts[0] ? sourceByKey(parts[0]) : undefined);
  const parent = $derived(parts[1] ? knownItems.get(`${parts[0]}/${parts[1]}`) : undefined);

  let groups = $state<{ source: MediaSource; items: MediaItem[]; error: string | null }[]>([]);
  let children = $state<MediaItem[] | null>(null);
  let error = $state<string | null>(null);

  $effect(() => {
    const list = $sources;
    if (parent || parts.length) return;
    groups = list.map((s) => ({ source: s, items: [], error: null }));
    list.forEach((s, i) =>
      s.libraries().then(
        (items) => (groups[i] = { source: s, items, error: null }),
        (e) => (groups[i] = { source: s, items: [], error: (e as Error).message }),
      ),
    );
  });

  $effect(() => {
    children = null;
    error = null;
    if (!source || !parent) return;
    source.children(parent).then(
      (items) => (children = items),
      (e) => (error = (e as Error).message),
    );
  });
</script>

<main class="library">
  {#if parts.length && !parent}
    <p class="dim">This page was opened directly. <a href="#/library">Back to the libraries</a></p>
  {:else if parent}
    <header>
      <a href="#/library">Libraries</a>
      <h1>{parent.name}</h1>
    </header>
    {#if error}<p class="error">{error}</p>{/if}
    {#if children === null && !error}<p class="dim">Loading…</p>{/if}
    {#if children}
      <ul class="grid">
        {#each children as item (item.id)}<MediaTile {item} />{/each}
      </ul>
      {#if children.length === 0}<p class="dim">Nothing here.</p>{/if}
    {/if}
  {:else}
    <header><h1>Libraries</h1></header>
    {#if $sources.length === 0}
      <section class="card">
        <p>Add a Jellyfin or Plex server to browse its libraries here.</p>
        <a class="button" href="#/servers">Media servers</a>
      </section>
    {/if}
    {#each groups as g (g.source.key)}
      <h2>{g.source.displayName}</h2>
      {#if g.error}<p class="error">{g.error}</p>{/if}
      <ul class="grid">
        {#each g.items as item (item.id)}<MediaTile {item} />{/each}
      </ul>
    {/each}
  {/if}
</main>

<style>
  .library {
    max-width: 1100px;
    margin: 0 auto;
    padding: 16px;
    display: grid;
    gap: 12px;
  }
  header {
    display: grid;
    gap: 4px;
  }
  h1 {
    margin: 0;
    font-size: 24px;
  }
  h2 {
    margin: 8px 0 0;
    font-size: 18px;
  }
  .grid {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 12px;
  }
  .error {
    color: var(--error);
  }
  .card {
    display: grid;
    gap: 10px;
    justify-items: start;
  }
  .card p {
    margin: 0;
  }
</style>
