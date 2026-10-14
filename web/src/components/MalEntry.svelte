<script lang="ts">
  import { mal } from "../app/watching";
  import { animeDetails, listSummary, MAL_STATUSES, malStatusLabel, statusValue, type MalAnime, type MalStatus } from "../lib/mal/models";

  /**
   * One MyAnimeList entry with your list status, editable (MobileMal.kt's entry): episodes watched, status, score,
   * and taking it off your list.
   */
  let { anime, compact = false }: { anime: MalAnime; compact?: boolean } = $props();
  /** The entry as last saved here, until another one is shown. */
  let edited = $state<MalAnime | null>(null);
  const entry = $derived(edited && edited.id === anime.id ? edited : anime);
  const list = $derived(entry.myListStatus);
  let busy = $state(false);
  let error = $state<string | null>(null);

  async function edit(update: Parameters<typeof mal.edit>[1]) {
    busy = true;
    error = null;
    try {
      await mal.edit(entry, update);
      edited = await mal.anime(entry.id);
    } catch (e) {
      error = (e as Error).message;
    } finally {
      busy = false;
    }
  }

  async function removeIt() {
    busy = true;
    try {
      if (await mal.remove(entry)) edited = { ...entry, myListStatus: null };
    } finally {
      busy = false;
    }
  }
</script>

<div class="entry" class:compact>
  {#if entry.mainPicture?.medium && !compact}<img src={entry.mainPicture.medium} alt="" />{/if}
  <div class="what">
    <a href="https://myanimelist.net/anime/{entry.id}" target="_blank" rel="noopener"><b>{entry.title}</b></a>
    <span class="dim small">{animeDetails(entry)}</span>
    <span class="small">{listSummary(entry)}</span>
    <div class="row">
      <label class="small">
        Status
        <select
          disabled={busy}
          value={list ? (statusValue(list) ?? "") : ""}
          onchange={(e) => edit({ status: (e.target as HTMLSelectElement).value as MalStatus })}
        >
          {#if !list}<option value="">Not on your list</option>{/if}
          {#each MAL_STATUSES as s (s)}<option value={s}>{malStatusLabel[s]}</option>{/each}
        </select>
      </label>
      <label class="small">
        Episodes
        <input
          type="number"
          min="0"
          max={entry.numEpisodes || undefined}
          value={list?.watchedEpisodes ?? 0}
          disabled={busy}
          onchange={(e) => edit({ watchedEpisodes: Number((e.target as HTMLInputElement).value) })}
        />
        {#if entry.numEpisodes}<span class="dim">/ {entry.numEpisodes}</span>{/if}
      </label>
      <label class="small">
        Score
        <select disabled={busy} value={String(list?.score ?? 0)} onchange={(e) => edit({ score: Number((e.target as HTMLSelectElement).value) })}>
          <option value="0">–</option>
          {#each [10, 9, 8, 7, 6, 5, 4, 3, 2, 1] as n (n)}<option value={String(n)}>{n}</option>{/each}
        </select>
      </label>
      {#if list}<button class="link" disabled={busy} onclick={removeIt}>Remove from list</button>{/if}
    </div>
    {#if error}<span class="error small">{error}</span>{/if}
  </div>
</div>

<style>
  .entry {
    display: flex;
    gap: 12px;
    align-items: flex-start;
  }
  img {
    width: 72px;
    border-radius: 8px;
    flex: none;
  }
  .what {
    display: grid;
    gap: 4px;
    min-width: 0;
  }
  .what a {
    color: var(--text);
    text-decoration: none;
    overflow-wrap: anywhere;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }
  label {
    display: inline-flex;
    gap: 4px;
    align-items: center;
  }
  select,
  input {
    background: var(--surface-high);
    border: 0;
    border-radius: 8px;
    padding: 4px 6px;
  }
  input {
    width: 5ch;
  }
  .small {
    font-size: 13px;
  }
  .link {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    font-size: 13px;
  }
  .error {
    color: var(--error);
  }
</style>
