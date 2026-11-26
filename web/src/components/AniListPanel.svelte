<script lang="ts">
  import { anilist } from "../app/watching";
  import { ANILIST_STATUSES, type AniListAnime, type AniListStatus } from "../lib/anilist/client";
  import type { AniListState } from "../lib/anilist/service";
  import Icon from "./Icon.svelte";

  let st = $state<AniListState>(anilist.state.value);
  let canUndo = $state(false);
  let picking = $state(false);
  let query = $state("");
  let results = $state<AniListAnime[]>([]);
  let episode = $state(1);

  $effect(() => anilist.state.subscribe((s) => (st = s)));
  $effect(() => anilist.canUndo.subscribe((u) => (canUndo = u)));
  $effect(() => {
    if (st.kind === "matched") episode = st.episode;
    else if ("info" in st && st.info.episode) episode = st.info.episode;
  });

  async function search() {
    if (query.trim().length >= 2) results = await anilist.client.search(query.trim(), 12).catch(() => []);
  }

  function startPicking(title: string) {
    picking = true;
    query = title;
    void search();
  }
</script>

<div class="panel">
  {#if st.kind === "notConfigured"}
    <p class="dim">AniList isn't available in this build.</p>
  {:else if st.kind === "signedOut"}
    <p>Sign in to keep your AniList up to date as you watch.</p>
    <a class="button" href="#/anilist"><Icon name="login" size={18} />Sign in on the AniList screen</a>
  {:else if st.kind === "idle"}
    <p class="dim">Nothing that's an episode of a show is playing.</p>
  {:else if st.kind === "matching"}
    <p class="dim">Finding {st.info.title} on AniList…</p>
  {:else if st.kind === "failed"}
    <p class="error">{st.message}</p>
    <button class="button quiet" onclick={() => anilist.refresh()}><Icon name="refresh" size={18} />Try again</button>
  {:else if st.kind === "ignored"}
    <p>{st.info.title} isn't tracked on AniList.</p>
    <button class="button quiet" onclick={() => st.kind === "ignored" && anilist.ignore(st.info.seriesKey, false)}>Track it again</button>
  {:else if st.kind === "matched"}
    {@const anime = st.entry}
    {@const entry = anime.listEntry}
    <div class="head">
      {#if anime.coverUrl}<img class="cover" src={anime.coverUrl} alt="" />{/if}
      <div>
        <b>{anime.title}</b>
        <p class="small dim">Episode {st.episode}{anime.episodes > 0 ? ` of ${anime.episodes}` : ""}</p>
        <p class="small dim">
          {entry ? `${entry.progress}${anime.episodes > 0 ? ` / ${anime.episodes}` : ""} watched${entry.score ? ` · ★ ${entry.score}` : ""}` : "Not on your list yet"}
        </p>
      </div>
    </div>
    <div class="row">
      <select
        value={entry?.status ?? ""}
        onchange={(e) => {
          const v = (e.target as HTMLSelectElement).value as AniListStatus;
          if (v) void anilist.update(anime, { status: v });
        }}
      >
        <option value="">Status…</option>
        {#each ANILIST_STATUSES as s (s.id)}<option value={s.id}>{s.label}</option>{/each}
      </select>
      <button class="button quiet small" disabled={(entry?.progress ?? 0) <= 0} onclick={() => void anilist.update(anime, { progress: Math.max(0, (entry?.progress ?? 0) - 1) })}>−1</button>
      <button class="button quiet small" disabled={anime.episodes > 0 && (entry?.progress ?? 0) >= anime.episodes} onclick={() => void anilist.update(anime, { progress: (entry?.progress ?? 0) + 1 })}>+1</button>
    </div>
    <div class="row">
      <button class="button small" onclick={() => st.kind === "matched" && void anilist.record(st.entry, st.episode)}>
        <Icon name="check_circle" size={18} />Mark ep {st.episode} watched
      </button>
      {#if canUndo}<button class="button quiet small" onclick={() => anilist.undo()}><Icon name="undo" size={18} />Undo</button>{/if}
      <button class="link" onclick={() => st.kind === "matched" && startPicking(st.info.title)}>Wrong entry?</button>
      <button class="link" onclick={() => st.kind === "matched" && anilist.ignore(st.info.seriesKey, true)}>Don't track</button>
    </div>
  {:else if st.kind === "unsure"}
    <p><b>Which AniList entry is this?</b> {st.info.title}{st.info.episode ? `, episode ${st.info.episode}` : ""}</p>
    <ul class="choices">
      {#each st.candidates as c (c.id)}
        <li><button class="link" onclick={() => st.kind === "unsure" && void anilist.choose(st.info, c, episode)}>{c.title}</button></li>
      {/each}
    </ul>
    <button class="link" onclick={() => st.kind === "unsure" && startPicking(st.info.title)}>Search for another</button>
  {/if}

  {#if picking && "info" in st}
    <form
      class="row"
      onsubmit={(e) => {
        e.preventDefault();
        void search();
      }}
    >
      <input bind:value={query} aria-label="Search AniList" />
      <label class="small">Ep <input type="number" min="1" bind:value={episode} class="num" /></label>
      <button class="button quiet small" type="submit"><Icon name="search" size={18} />Search</button>
    </form>
    <ul class="choices">
      {#each results as r (r.id)}
        <li>
          <button
            class="link"
            onclick={() => {
              if ("info" in st) void anilist.choose(st.info, r, episode);
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
  .head {
    display: flex;
    gap: 10px;
    align-items: flex-start;
  }
  .cover {
    width: 56px;
    height: 80px;
    border-radius: 6px;
    object-fit: cover;
    background: var(--surface-high);
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
    padding: 6px 10px;
    font-size: 13px;
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
  input,
  select {
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
