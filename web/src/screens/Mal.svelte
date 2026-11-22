<script lang="ts">
  import { go } from "../app/context";
  import Icon from "../components/Icon.svelte";
  import { session } from "../app/context";
  import { settings } from "../app/settings";
  import { chooseMalServer, mal, malClient, malServer, malServerChoices, signInToMal, type MalServerChoice } from "../app/watching";
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

  // Which server passes the account's requests on: the user's choice, never the room they happen to be in.
  let server = $state<string | null>(malServer.value);
  let choices = $state<MalServerChoice[]>(malServerChoices());
  /** Ticked once the user has read what the chosen server gets; needed to sign in. */
  let understood = $state(false);
  $effect(() => malServer.subscribe((s) => (server = s)));
  $effect(() => {
    const refresh = () => (choices = malServerChoices());
    const stops = [settings.subscribe(refresh), session.subscribe(refresh), malServer.subscribe(refresh)];
    return () => stops.forEach((stop) => stop());
  });
  let picked = $state<string>(malServer.value ?? "");
  const pickedLabel = $derived(choices.find((c) => c.base === picked)?.label ?? "");

  function changeServer(base: string) {
    picked = base;
    if (signedIn) chooseMalServer(base || null);
  }

  function signIn() {
    if (!picked || !understood) return;
    chooseMalServer(picked);
    signInToMal();
  }

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
      <p class="small">
        In the browser this goes through a server you choose. MyAnimeList doesn't accept requests from web pages, so the
        browser asks a YarmiplayServerTV to pass them on. That server handles your MyAnimeList sign-in and sees your list
        and the changes you make; only the server you choose here gets them, never the rooms you join. The apps for
        Android and computers talk to MyAnimeList directly.
      </p>
      {#if choices.length}
        <label class="row small">
          Server
          <select value={picked} onchange={(e) => changeServer((e.target as HTMLSelectElement).value)}>
            <option value="">Choose a server…</option>
            {#each choices as c (c.base)}<option value={c.base}>{c.label}{c.inRoom ? " (this room's)" : ""}</option>{/each}
          </select>
        </label>
        <label class="switch small">
          <input type="checkbox" bind:checked={understood} disabled={!picked} />
          {pickedLabel ? `${pickedLabel} may handle my MyAnimeList sign-in and see my list` : "Choose a server first"}
        </label>
      {:else}
        <p class="dim small">Join a room on a YarmiplayServerTV first: its server, or a saved room's, can then be chosen.</p>
      {/if}
      <p class="dim small">You sign in on MyAnimeList's own page; YarmiplayTV never sees your password.</p>
      <button class="button" onclick={signIn} disabled={!picked || !understood}><Icon name="login" size={18} />Sign in to MyAnimeList</button>
    </section>
  {:else}
    <section class="card group">
      <div class="row">
        {#if prefs.account?.picture}<img class="avatar" src={prefs.account.picture} alt="" />{/if}
        <b class="grow">{prefs.account?.userName}</b>
        <button class="link" onclick={() => mal.signOut()}><Icon name="logout" size={18} />Sign out</button>
      </div>
      <label class="row small">
        Through
        <select value={server ?? ""} onchange={(e) => changeServer((e.target as HTMLSelectElement).value)}>
          <option value="">No server (the list isn't updated)</option>
          {#each choices as c (c.base)}<option value={c.base}>{c.label}{c.inRoom ? " (this room's)" : ""}</option>{/each}
        </select>
      </label>
      {#if !server}
        <p class="small error">Choose the server that passes your MyAnimeList requests on; until then nothing is sent with your account.</p>
      {:else}
        <p class="dim small">Your MyAnimeList sign-in, list and updates go only through this server.</p>
      {/if}
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
        <button class="button quiet" type="submit" disabled={query.trim().length < 3}><Icon name="search" size={18} />Search</button>
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
          <button class="link" onclick={() => go(`#/search?q=${encodeURIComponent(a.title)}`)}><Icon name="search" size={18} />Find it in your libraries</button>
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
