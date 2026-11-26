<script lang="ts">
  import { onMount } from "svelte";
  import { player, session as sessionStore, settings } from "../app/context";
  import { subtitleSearch } from "../app/watching";
  import * as FileNames from "../lib/media/fileNames";
  import { FileSource } from "../lib/player/bytes";
  import {
    SUBTITLE_LANGUAGES,
    computeOpenSubtitlesHash,
    type SubtitleSearchResult,
  } from "../lib/subtitles/search";

  let { onBack, onAttached }: { onBack: () => void; onAttached: () => void } = $props();

  let query = $state("");
  let language = $state($settings.playback.onlineSubtitlesLanguage || "en");
  let season = $state<number | null>(null);
  let episode = $state<number | null>(null);
  let movieHash = $state<string | null>(null);
  let initialQuery = $state("");
  let results = $state<SubtitleSearchResult[]>([]);
  let loading = $state(false);
  let downloadingId = $state<string | null>(null);
  let error = $state<string | null>(null);

  async function runSearch(useHash = false) {
    if (!subtitleSearch.configured) {
      error = "Online subtitle search isn't configured in this build.";
      return;
    }
    loading = true;
    error = null;
    try {
      const found = await subtitleSearch.search({
        query: query.trim(),
        language,
        season,
        episode,
        movieHash: useHash ? movieHash : null,
        fileName: $sessionStore?.nowPlaying.value?.fileName ?? null,
      });
      results = found;
      if (found.length === 0) error = "No subtitles found.";
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  async function pick(item: SubtitleSearchResult) {
    if (downloadingId) return;
    downloadingId = item.id;
    error = null;
    try {
      const dl = await subtitleSearch.download(item);
      await player.value?.addExternalSubtitle(dl.fileName, dl.text, dl.language, true);
      onAttached();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      downloadingId = null;
    }
  }

  onMount(() => {
    void (async () => {
      const np = $sessionStore?.nowPlaying.value;
      const ep = np?.episode ?? null;
      season = ep?.season ?? null;
      episode = ep?.episode ?? null;
      const parsed = np?.fileName ? FileNames.parse(np.fileName) : null;
      query = ep?.seriesName || parsed?.title || np?.fileName || "";
      initialQuery = query;
      const src = player.value?.source;
      if (src instanceof FileSource) {
        movieHash = await computeOpenSubtitlesHash(src.file).catch(() => null);
      }
      if (query || movieHash) await runSearch(true);
    })();
  });
</script>

<div class="subsearch" data-testid="online_subtitles_panel">
  <div class="bar">
    <button class="link" onclick={onBack}>← Back</button>
    <span class="title">Online subtitles</span>
  </div>
  <form
    class="controls"
    onsubmit={(e) => {
      e.preventDefault();
      void runSearch(query.trim() === initialQuery);
    }}
  >
    <input bind:value={query} placeholder="Title or release" aria-label="Subtitle search query" />
    <select bind:value={language} onchange={() => void runSearch(query.trim() === initialQuery)} aria-label="Subtitle language">
      {#each SUBTITLE_LANGUAGES as lang (lang.code)}
        <option value={lang.code}>{lang.label}</option>
      {/each}
    </select>
    <button class="button small" type="submit" disabled={loading || !query.trim()}>Search</button>
  </form>
  {#if loading}
    <p class="dim small">Searching subtitles…</p>
  {/if}
  {#if error}
    <p class="dim small">{error}</p>
  {/if}
  <ul class="results">
    {#each results as item (item.id)}
      <li>
        <button class="result" disabled={downloadingId !== null} onclick={() => void pick(item)}>
          <span class="name">{downloadingId === item.id ? "Downloading… " : ""}{item.releaseName}</span>
          <span class="meta" class:exact={item.exactHashMatch}>
            {[
              item.exactHashMatch ? "Exact match" : null,
              item.provider === "opensubtitles" ? "OpenSubtitles" : "Subdl",
              item.language.toUpperCase(),
              item.hearingImpaired ? "HI" : null,
              item.downloads > 0 ? `${item.downloads} dl` : null,
            ]
              .filter(Boolean)
              .join(" · ")}
          </span>
        </button>
      </li>
    {/each}
  </ul>
</div>

<style>
  .subsearch {
    display: grid;
    gap: 12px;
    padding: 4px 14px 16px;
    overflow: auto;
    align-content: start;
  }
  .bar {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .title {
    font-weight: 600;
  }
  .controls {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 8px;
  }
  .controls input {
    grid-column: 1 / -1;
    background: var(--surface-high);
    border: 0;
    border-radius: 8px;
    padding: 9px 11px;
  }
  .controls select {
    min-width: 0;
    background: var(--surface-high);
    border: 0;
    border-radius: 8px;
    padding: 8px 10px;
  }
  .button.small {
    padding: 8px 14px;
    font-size: 13px;
  }
  .link {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    padding: 4px 0;
  }
  .results {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 8px;
  }
  .result {
    width: 100%;
    text-align: left;
    display: grid;
    gap: 4px;
    padding: 10px 12px;
    border: 0;
    border-radius: 10px;
    background: var(--surface-high);
    color: var(--on-surface, var(--text));
    cursor: pointer;
  }
  .name {
    font-size: 13px;
    word-break: break-word;
  }
  .meta {
    font-size: 11px;
    color: var(--text-dim);
  }
  .meta.exact {
    color: var(--accent);
  }
  .small {
    font-size: 13px;
    margin: 0;
  }
</style>
