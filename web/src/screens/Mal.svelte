<script lang="ts">
  import { go } from "../app/context";
  import { mal, malClient, signInToMal } from "../app/watching";
  import MalEntry from "../components/MalEntry.svelte";
  import { MAL_STATUSES, malStatusLabel, type MalAnime, type MalStatus } from "../lib/mal/models";
  import type { MalSettings } from "../lib/mal/storage";

  /**
   * The MyAnimeList screen (MobileMal.kt): the account, updating the list as you watch (or asking first), finding any
   * show, your lists, the shows you don't track and updates waiting to be sent.
   */
  let prefs = $state<MalSettings>(mal.store.settings.value);
  $effect(() => mal.store.settings.subscribe((s) => (prefs = s)));

  let status = $state<MalStatus>("watching");
  let list = $state<MalAnime[] | null>(null);
  let listError = $state<string | null>(null);
  let query = $state("");
  let found = $state<MalAnime[] | null>(null);
  let searchError = $state<string | null>(null);

  const signedIn = $derived(prefs.account !== null);

  $effect(() => {
    if (!signedIn) return;
    list = null;
    listError = null;
    mal.myList(status).then(
      (rows) => (list = rows.map(([anime, s]) => ({ ...anime, myListStatus: s }))),
      (e) => (listError = (e as Error).message),
    );
  });

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
  {:else if !signedIn}
    <section class="card group">
      <p>Keep your MyAnimeList list up to date as you watch: when you've watched 90% of an episode, the app updates it.</p>
      <p class="dim small">You sign in on MyAnimeList's own page; YarmiplayTV never sees your password. MyAnimeList's requests go through the YarmiplayServerTV of your last room.</p>
      <button class="button" onclick={signInToMal}>Sign in to MyAnimeList</button>
    </section>
  {:else}
    <section class="card group">
      <div class="row">
        {#if prefs.account?.picture}<img class="avatar" src={prefs.account.picture} alt="" />{/if}
        <b class="grow">{prefs.account?.userName}</b>
        <button class="link" onclick={() => mal.signOut()}>Sign out</button>
      </div>
      <label class="switch"><input type="checkbox" checked={prefs.autoUpdate} onchange={(e) => mal.savePrefs((e.target as HTMLInputElement).checked, prefs.askFirst)} /> Update my list as I watch</label>
      <label class="switch"><input type="checkbox" checked={prefs.askFirst} onchange={(e) => mal.savePrefs(prefs.autoUpdate, (e.target as HTMLInputElement).checked)} /> Ask before each update</label>
      {#if prefs.queue.length}<p class="small">{prefs.queue.length} update{prefs.queue.length === 1 ? "" : "s"} waiting to be sent. <button class="link" onclick={() => mal.flushQueue()}>Send now</button></p>{/if}
    </section>
  {/if}

  {#if malClient.configured}
    <section class="card group">
      <h2>Find a show</h2>
      <form class="row" onsubmit={search}>
        <input class="grow" bind:value={query} placeholder="At least 3 letters" aria-label="Find a show on MyAnimeList" />
        <button class="button quiet" type="submit" disabled={query.trim().length < 3}>Search</button>
      </form>
      {#if searchError}<p class="error">{searchError}</p>{/if}
      {#if found}
        {#each found as a (a.id)}<MalEntry anime={a} />{/each}
        {#if found.length === 0}<p class="dim">Nothing found.</p>{/if}
      {/if}
    </section>
  {/if}

  {#if signedIn}
    <section class="card group">
      <div class="tabs">
        {#each MAL_STATUSES.filter((s) => s === "watching" || s === "plan_to_watch" || s === "completed") as s (s)}
          <button class:on={status === s} onclick={() => (status = s)}>{malStatusLabel[s]}</button>
        {/each}
      </div>
      {#if listError}<p class="error">{listError}</p>{/if}
      {#if list === null && !listError}<p class="dim">Loading…</p>{/if}
      {#each list ?? [] as a (a.id)}
        <div class="list-row">
          <MalEntry anime={a} compact />
          <button class="link" onclick={() => go(`#/search?q=${encodeURIComponent(a.title)}`)}>Find it in your libraries</button>
        </div>
      {/each}
      {#if list?.length === 0}<p class="dim">Nothing here.</p>{/if}
    </section>

    {#if prefs.ignored.length}
      <section class="card group">
        <h2>Shows you don't track</h2>
        {#each prefs.ignored as key (key)}
          <div class="row">
            <span class="grow">{prefs.ignoredNames[key] || key}</span>
            <button class="link" onclick={() => mal.ignore(key, false)}>Track again</button>
          </div>
        {/each}
      </section>
    {/if}
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
  .avatar {
    width: 40px;
    height: 40px;
    border-radius: 50%;
  }
  .switch {
    display: flex;
    gap: 10px;
    align-items: center;
  }
  input:not([type="checkbox"]) {
    background: var(--surface-high);
    border: 0;
    border-radius: 10px;
    padding: 10px 12px;
  }
  .small {
    font-size: 13px;
  }
  .link {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    font-size: 14px;
  }
  .tabs {
    display: flex;
    gap: 4px;
  }
  .tabs button {
    background: var(--surface-high);
    border: 0;
    border-radius: 999px;
    padding: 6px 14px;
    cursor: pointer;
  }
  .tabs button.on {
    background: var(--accent);
    color: var(--on-accent);
  }
  .list-row {
    display: grid;
    gap: 4px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--surface-high);
  }
  .error {
    color: var(--error);
  }
</style>
