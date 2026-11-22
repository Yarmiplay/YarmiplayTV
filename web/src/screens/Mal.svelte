<script lang="ts">
  import Icon from "../components/Icon.svelte";
  import { mal, malClient } from "../app/watching";
  import MalEntry from "../components/MalEntry.svelte";
  import type { MalAnime } from "../lib/mal/models";

  /**
   * The MyAnimeList screen: why the browser doesn't sign in to MyAnimeList (the apps' MobileMal.kt does), and
   * finding any show, which needs no account.
   */
  let query = $state("");
  let found = $state<MalAnime[] | null>(null);
  let searchError = $state<string | null>(null);

  async function search(event: SubmitEvent) {
    event.preventDefault();
    searchError = null;
    try {
      found = await mal.findShows(query);
    } catch (e) {
      searchError = (e as Error).message;
    }
  }
</script>

<main class="mal">
  <h1>MyAnimeList</h1>

  {#if !malClient.configured}
    <p class="card dim">MyAnimeList isn't available in this build.</p>
  {:else}
    <section class="card group">
      <p>
        Signing in to MyAnimeList, and updating your list as you watch, is only in the YarmiplayTV apps for Android and
        computers. MyAnimeList doesn't accept requests from web pages, so in the browser your MyAnimeList sign-in would
        have to pass through the Syncplay room's server, and YarmiplayTV won't hand your MyAnimeList account to a room's
        server.
      </p>
      <p class="dim small">
        Find a show below, and skipping anime openings and endings with AniSkip, need no account: they ask MyAnimeList
        for public details through the room's YarmiplayServerTV.
      </p>
    </section>
  {/if}

  {#if malClient.configured}
    <section class="card group">
      <h2>Find a show</h2>
      <form class="row" onsubmit={search}>
        <input class="grow" bind:value={query} placeholder="At least 3 letters" aria-label="Find a show on MyAnimeList" />
        <button class="button quiet" type="submit" disabled={query.trim().length < 3}><Icon name="search" size={18} />Search</button>
      </form>
      {#if searchError}<p class="error">{searchError}</p>{/if}
      {#if found}
        {#each found as a (a.id)}<MalEntry anime={a} />{/each}
        {#if found.length === 0}<p class="dim">Nothing found.</p>{/if}
      {/if}
    </section>
  {/if}

</main>

<style>
  .mal {
    max-width: 820px;
    margin: 0 auto;
    padding: 16px;
    display: grid;
    gap: 16px;
  }
  h1 {
    margin: 0;
    font-size: 24px;
  }
  h2 {
    margin: 0;
    font-size: 18px;
  }
  p {
    margin: 0;
  }
  .group {
    display: grid;
    gap: 12px;
  }
  .row {
    display: flex;
    gap: 8px;
    align-items: center;
    flex-wrap: wrap;
  }
  .grow {
    flex: 1;
    min-width: 0;
  }
  input {
    background: var(--surface-high);
    border: 0;
    border-radius: 10px;
    padding: 10px 12px;
  }
  .small {
    font-size: 13px;
  }
  .error {
    color: var(--error);
  }
</style>
