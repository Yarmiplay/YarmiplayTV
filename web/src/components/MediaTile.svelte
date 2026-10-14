<script lang="ts" module>
  import type { MediaItem } from "../lib/media";

  /** Items seen while browsing, by "<server key>/<item id>", so a route can name one. */
  export const knownItems = new Map<string, MediaItem>();
</script>

<script lang="ts">
  import { go, session as sessionStore } from "../app/context";
  import { sourceByKey } from "../app/media";
  import { displayTitle, isPlayable } from "../lib/media";

  /** One library item (MobileBrowse.kt's tiles): a folder opens, a video offers play in the room, play here, or add. */
  let { item, substitute = false }: { item: MediaItem; substitute?: boolean } = $props();
  const session = $derived($sessionStore);
  let connected = $state(false);
  $effect(() => {
    if (!session) return;
    return session.room.subscribe((r) => (connected = r.status === "CONNECTED"));
  });

  const source = $derived(sourceByKey(item.sourceKey));
  const image = $derived(source?.imageUrl(item, 400) ?? null);
  const playable = $derived(isPlayable(item));
  $effect(() => {
    knownItems.set(`${item.sourceKey}/${item.id}`, item);
  });

  async function act(mode: "room" | "here" | "add") {
    if (!source || !session) return;
    if (mode !== "add") go("#/player");
    await session.playItem(source, item, mode);
  }

  async function pick() {
    if (!source || !session) return;
    go("#/player");
    await session.resolveManuallyItem(source, item);
  }
</script>

<li class="card tile">
  {#if playable}
    <div class="thumb">{#if image}<img src={image} alt="" loading="lazy" />{/if}</div>
  {:else}
    <a class="thumb" href="#/library/{encodeURIComponent(item.sourceKey)}/{encodeURIComponent(item.id)}">
      {#if image}<img src={image} alt="" loading="lazy" />{/if}
    </a>
  {/if}
  <b class="name">
    {#if playable}{displayTitle(item)}{:else}<a href="#/library/{encodeURIComponent(item.sourceKey)}/{encodeURIComponent(item.id)}">{item.name}</a>{/if}
  </b>
  <span class="dim small">
    {[item.seriesName, item.year, source?.displayName].filter(Boolean).join(" · ")}
  </span>
  {#if playable}
    <div class="row">
      {#if substitute}
        <button class="button small" onclick={pick}>Play as the room's entry</button>
      {:else}
        {#if connected}
          <button class="button small" onclick={() => act("room")}>Play in the room</button>
          <button class="button quiet small" onclick={() => act("add")}>Add to playlist</button>
        {/if}
        <button class="button quiet small" onclick={() => act("here")}>Play here</button>
      {/if}
    </div>
  {/if}
</li>

<style>
  .tile {
    display: grid;
    gap: 6px;
    align-content: start;
  }
  .thumb {
    display: block;
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
  .name a {
    color: var(--text);
    text-decoration: none;
  }
  .small {
    font-size: 13px;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .button.small {
    padding: 6px 12px;
    font-size: 14px;
  }
</style>
