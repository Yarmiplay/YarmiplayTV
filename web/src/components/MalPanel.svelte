<script lang="ts">
  import { mal, signInToMal } from "../app/watching";
  import Icon from "./Icon.svelte";
  import type { MalAnime } from "../lib/mal/models";
  import type { MalState } from "../lib/mal/service";
  import MalEntry from "./MalEntry.svelte";

  /**
   * The player's MyAnimeList panel (MobileMal.kt's in-player panel): the playing episode's entry, marking it watched,
   * Undo, "Wrong entry?" and "Don't track this show".
   */
  let ms = $state<MalState>(mal.state.value);
  let canUndo = $state(false);
  let picking = $state(false);
  let query = $state("");
  let results = $state<MalAnime[]>([]);
  let episode = $state(1);

  $effect(() => mal.state.subscribe((s) => (ms = s)));
  $effect(() => mal.canUndo.subscribe((u) => (canUndo = u)));
  $effect(() => {
    if (ms.kind === "matched") episode = ms.match.episode;
    else if ("info" in ms && ms.info.episode) episode = ms.info.episode;
  });

  async function search() {
    if (query.trim().length >= 3) results = await mal.search(query.trim()).catch(() => []);
  }

  function startPicking(title: string) {
    picking = true;
    query = title;
    void search();
  }
</script>

<div class="panel">
  {#if ms.kind === "notConfigured"}
    <p class="dim">MyAnimeList isn't available in this build.</p>
  {:else if ms.kind === "signedOut"}
    <p>Sign in to keep your MyAnimeList list up to date as you watch.</p>
    <button class="button" onclick={signInToMal}><Icon name="login" size={18} />Sign in to MyAnimeList</button>
  {:else if ms.kind === "idle"}
    <p class="dim">Nothing that's an episode of a show is playing.</p>
  {:else if ms.kind === "matching"}
    <p class="dim">Finding {ms.info.title} on MyAnimeList…</p>
  {:else if ms.kind === "failed"}
    <p class="error">{ms.message}</p>
    <button class="button quiet" onclick={() => mal.refresh()}><Icon name="refresh" size={18} />Try again</button>
  {:else if ms.kind === "ignored"}
    <p>{ms.info.title} isn't tracked.</p>
    <button class="button quiet" onclick={() => mal.ignore(ms.kind === "ignored" ? ms.info.seriesKey : "", false)}><Icon name="visibility_off" size={18} />Track it again</button>
  {:else if ms.kind === "matched"}
    <MalEntry anime={ms.entry} />
    <p class="small dim">This is episode {ms.match.episode} of it.</p>
    <div class="row">
      <button class="button small" onclick={() => ms.kind === "matched" && mal.watched(ms.info)}><Icon name="check_circle" size={18} />Mark episode {ms.match.episode} watched</button>
      {#if canUndo}<button class="button quiet small" onclick={() => mal.undo()}><Icon name="undo" size={18} />Undo</button>{/if}
      <button class="link" onclick={() => ms.kind === "matched" && startPicking(ms.info.title)}>Wrong entry?</button>
      <button class="link" onclick={() => ms.kind === "matched" && mal.ignore(ms.info.seriesKey, true, ms.info.title)}>Don't track this show</button>
    </div>
  {:else if ms.kind === "unsure"}
    <p><b>Which MyAnimeList entry is this?</b> {ms.info.title}{ms.info.episode ? `, episode ${ms.info.episode}` : ""}</p>
    <ul class="choices">
      {#each ms.candidates as c (c.id)}
        <li><button class="link" onclick={() => ms.kind === "unsure" && mal.choose(ms.info, c, episode)}>{c.title}</button></li>
      {/each}
    </ul>
    <button class="link" onclick={() => ms.kind === "unsure" && startPicking(ms.info.title)}>Search for another</button>
  {/if}

  {#if picking && "info" in ms}
    <form
      class="row"
      onsubmit={(e) => {
        e.preventDefault();
        void search();
      }}
    >
      <input bind:value={query} aria-label="Search MyAnimeList" />
      <label class="small">Episode <input type="number" min="1" bind:value={episode} class="num" /></label>
      <button class="button quiet small" type="submit"><Icon name="search" size={18} />Search</button>
    </form>
    <ul class="choices">
      {#each results as r (r.id)}
        <li>
          <button
            class="link"
            onclick={() => {
              if ("info" in ms) void mal.choose(ms.info, r, episode);
              picking = false;
            }}>{r.title}</button
          >
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .panel {
    display: grid;
    gap: 10px;
    padding: 0 16px 16px;
    overflow: auto;
    align-content: start;
  }
  p {
    margin: 0;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }
  .small {
    font-size: 13px;
  }
  .button.small {
    padding: 6px 12px;
    font-size: 14px;
  }
  .link {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    text-align: left;
    padding: 2px 0;
  }
  .choices {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 4px;
  }
  input {
    background: var(--surface-high);
    border: 0;
    border-radius: 8px;
    padding: 6px 8px;
  }
  .num {
    width: 6ch;
  }
  .error {
    color: var(--error);
  }
</style>
