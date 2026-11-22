<script lang="ts">
  import { animeDetails, type MalAnime } from "../lib/mal/models";

  /**
   * One MyAnimeList entry as public data: its picture, title (linking to its MyAnimeList page) and details. The
   * browser doesn't sign in to MyAnimeList, so there's no list entry to show or change here (MobileMal.kt's has one).
   */
  let { anime: entry }: { anime: MalAnime } = $props();
</script>

<div class="entry">
  {#if entry.mainPicture?.medium}<img src={entry.mainPicture.medium} alt="" />{/if}
  <div class="what">
    <a href="https://myanimelist.net/anime/{entry.id}" target="_blank" rel="noopener"><b>{entry.title}</b></a>
    <span class="dim small">{animeDetails(entry)}</span>
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
  .small {
    font-size: 13px;
  }
</style>
