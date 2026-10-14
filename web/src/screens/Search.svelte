<script lang="ts">
  import { route, session as sessionStore } from "../app/context";
  import { sources } from "../app/media";
  import MediaTile from "../components/MediaTile.svelte";
  import type { PlaylistStatus } from "../lib/sync/room";
  import type { MediaItem } from "../lib/media";

  /**
   * Searching every media server at once (MobileBrowse.kt's search). While a playlist entry couldn't be found, it
   * also offers to play a result as that entry.
   */
  const session = $derived($sessionStore);
  let status = $state<PlaylistStatus>({ kind: "idle" });
  let query = $state(new URLSearchParams($route.split("?")[1] ?? "").get("q") ?? "");
  let results = $state<MediaItem[] | null>(null);
  let errors = $state<string[]>([]);
  let searching = $state(false);
  let timer: ReturnType<typeof setTimeout> | undefined;

  $effect(() => {
    if (!session) return;
    return session.status.subscribe((s) => {
      status = s;
      if (s.kind === "notFound" && !query) query = s.fileName.replace(/\.[^.]+$/, "").replace(/[._]/g, " ");
    });
  });

  $effect(() => {
    const q = query.trim();
    clearTimeout(timer);
    if (q.length < 2) {
      results = null;
      return;
    }
    timer = setTimeout(() => void search(q), 300);
  });

  async function search(q: string) {
    searching = true;
    const all = await Promise.all(
      $sources.map((s) =>
        s.search(q, 40).then(
          (items) => ({ items, error: null as string | null }),
          (e) => ({ items: [] as MediaItem[], error: `${s.displayName}: ${(e as Error).message}` }),
        ),
      ),
    );
    if (q !== query.trim()) return;
    results = all.flatMap((r) => r.items);
    errors = all.flatMap((r) => (r.error ? [r.error] : []));
    searching = false;
  }
</script>

<main class="search">
  <h1>Search</h1>
  {#if status.kind === "notFound"}
    <p class="card">Looking for something to play as <b class="mono">{status.fileName}</b>.</p>
  {/if}
  <input placeholder="Search your media servers" bind:value={query} aria-label="Search" />
  {#if $sources.length === 0}
    <p class="dim">Add a Jellyfin or Plex server to search it. <a href="#/servers">Media servers</a></p>
  {/if}
  {#each errors as e (e)}<p class="error">{e}</p>{/each}
  {#if searching && !results}<p class="dim">Searching…</p>{/if}
  {#if results}
    <ul class="grid">
      {#each results as item (item.sourceKey + item.id)}<MediaTile {item} substitute={status.kind === "notFound"} />{/each}
    </ul>
    {#if results.length === 0}<p class="dim">Nothing found.</p>{/if}
  {/if}
</main>

<style>
  .search {
    max-width: 1100px;
    margin: 0 auto;
    padding: 16px;
    display: grid;
    gap: 12px;
  }
  h1 {
    margin: 0;
    font-size: 24px;
  }
  p {
    margin: 0;
  }
  input {
    background: var(--surface-high);
    border: 0;
    border-radius: 999px;
    padding: 12px 16px;
    font-size: 17px;
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
</style>
