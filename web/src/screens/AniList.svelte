<script lang="ts">
  import { go } from "../app/context";
  import { anilist } from "../app/watching";
  import Icon from "../components/Icon.svelte";
  import { ANILIST_STATUSES, type AniListAnime, type AniListSettings, type AniListStatus } from "../lib/anilist/client";

  let prefs = $state<AniListSettings>(anilist.settingsStore.value);
  $effect(() => anilist.settingsStore.subscribe((s) => (prefs = s)));

  let status = $state<AniListStatus>("CURRENT");
  let list = $state<AniListAnime[] | null>(null);
  let listError = $state<string | null>(null);
  let query = $state("");
  let found = $state<AniListAnime[] | null>(null);
  let searchError = $state<string | null>(null);
  let manualToken = $state("");
  let authError = $state<string | null>(null);

  const signedIn = $derived(prefs.account !== null);

  function loadList() {
    const uid = prefs.account?.userId;
    if (!uid) return;
    list = null;
    listError = null;
    anilist.client.myList(uid, status).then(
      (rows) => (list = rows),
      (e) => (listError = e instanceof Error ? e.message : String(e)),
    );
  }

  $effect(() => {
    void status;
    if (signedIn) loadList();
  });

  async function search(event: SubmitEvent) {
    event.preventDefault();
    searchError = null;
    try {
      found = await anilist.client.search(query, 20);
    } catch (e) {
      searchError = e instanceof Error ? e.message : String(e);
    }
  }

  async function connectManual(event: SubmitEvent) {
    event.preventDefault();
    authError = null;
    try {
      await anilist.signInWithInput(manualToken);
      manualToken = "";
    } catch (e) {
      authError = e instanceof Error ? e.message : String(e);
    }
  }

  async function stepProgress(anime: AniListAnime, delta: number) {
    const cur = anime.listEntry?.progress ?? 0;
    const next = Math.max(0, cur + delta);
    const finished = anime.episodes > 0 && next >= anime.episodes;
    await anilist.update(anime, {
      status: finished ? "COMPLETED" : anime.listEntry?.status ?? "CURRENT",
      progress: next,
    });
    loadList();
  }
</script>

<main class="al">
  <h1>AniList</h1>

  {#if !anilist.client.configured}
    <p class="card dim">AniList isn't available in this build.</p>
  {:else if !signedIn}
    <section class="card group">
      <p>Keep your AniList up to date as you watch: after 90% of an episode plays, YarmiplayTV updates your progress automatically.</p>
      <p class="dim small">
        Your browser talks to AniList directly (graphql.anilist.co) using OAuth; your token never passes through a Syncplay server and is cleared when you sign out.
      </p>
      <a class="button" href={anilist.beginSignIn()}><Icon name="login" size={18} />Sign in to AniList</a>
      <form class="row" onsubmit={connectManual}>
        <input class="grow" bind:value={manualToken} placeholder="Or paste access token / redirect URL" aria-label="AniList access token" />
        <button class="button quiet" type="submit" disabled={manualToken.trim().length < 20}>Connect</button>
      </form>
      {#if authError}<p class="error small">{authError}</p>{/if}
    </section>
  {:else}
    <section class="card group">
      <div class="row">
        {#if prefs.account?.avatarUrl}<img class="avatar" src={prefs.account.avatarUrl} alt="" />{/if}
        <b class="grow">{prefs.account?.userName}</b>
        <button class="link" onclick={() => anilist.signOut()}><Icon name="logout" size={18} />Sign out</button>
      </div>
      <label class="switch">
        <input type="checkbox" checked={prefs.autoUpdate} onchange={(e) => anilist.savePrefs((e.target as HTMLInputElement).checked, prefs.askFirst)} />
        Update my list as I watch
      </label>
      <label class="switch">
        <input type="checkbox" checked={prefs.askFirst} onchange={(e) => anilist.savePrefs(prefs.autoUpdate, (e.target as HTMLInputElement).checked)} />
        Ask before each update
      </label>
    </section>
  {/if}

  {#if anilist.client.configured}
    <section class="card group">
      <h2>Find a show</h2>
      <form class="row" onsubmit={search}>
        <input class="grow" bind:value={query} placeholder="Search AniList" aria-label="Find a show on AniList" />
        <button class="button quiet" type="submit" disabled={query.trim().length < 2}><Icon name="search" size={18} />Search</button>
      </form>
      {#if searchError}<p class="error">{searchError}</p>{/if}
      {#if found}
        {#each found as a (a.id)}
          <div class="entry">
            {#if a.coverUrl}<img class="cover" src={a.coverUrl} alt="" />{/if}
            <div class="grow">
              <b>{a.title}</b>
              <div class="dim small">{[a.format, a.seasonYear, a.episodes > 0 ? `${a.episodes} ep` : null].filter(Boolean).join(" · ")}</div>
            </div>
            {#if signedIn}
              <button class="button quiet small" onclick={() => void stepProgress(a, 1)}>+1</button>
            {/if}
          </div>
        {/each}
        {#if found.length === 0}<p class="dim">Nothing found.</p>{/if}
      {/if}
    </section>
  {/if}

  {#if signedIn}
    <section class="card group">
      <div class="tabs">
        {#each ANILIST_STATUSES.filter((s) => s.id === "CURRENT" || s.id === "PLANNING" || s.id === "COMPLETED") as st (st.id)}
          <button class:on={status === st.id} onclick={() => (status = st.id)}>{st.label}</button>
        {/each}
      </div>
      {#if listError}<p class="error">{listError}</p>{/if}
      {#if list === null && !listError}<p class="dim">Loading…</p>{/if}
      {#each list ?? [] as a (a.id)}
        <div class="entry">
          {#if a.coverUrl}<img class="cover" src={a.coverUrl} alt="" />{/if}
          <div class="grow">
            <b>{a.title}</b>
            <div class="dim small">
              {a.listEntry?.progress ?? 0}{a.episodes > 0 ? ` / ${a.episodes}` : ""} watched{a.listEntry?.score ? ` · ★ ${a.listEntry.score}` : ""}
            </div>
            <button class="link small" onclick={() => go(`#/search?q=${encodeURIComponent(a.title)}`)}>Find in libraries</button>
          </div>
          <button class="button quiet small" disabled={(a.listEntry?.progress ?? 0) <= 0} onclick={() => void stepProgress(a, -1)}>−1</button>
          <button class="button quiet small" disabled={a.episodes > 0 && (a.listEntry?.progress ?? 0) >= a.episodes} onclick={() => void stepProgress(a, 1)}>+1</button>
        </div>
      {/each}
      {#if list?.length === 0}<p class="dim">Nothing here.</p>{/if}
    </section>

    {#if prefs.ignored.length}
      <section class="card group">
        <h2>Shows you don't track</h2>
        {#each prefs.ignored as key (key)}
          <div class="row">
            <span class="grow">{key}</span>
            <button class="link" onclick={() => anilist.ignore(key, false)}>Track again</button>
          </div>
        {/each}
      </section>
    {/if}
  {/if}
</main>

<style>
  .al {
    max-width: 820px;
    margin: 0 auto;
    padding: 16px;
    display: grid;
    gap: 16px;
  }
  .group {
    display: grid;
    gap: 12px;
  }
  .group p,
  h1,
  h2 {
    margin: 0;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }
  .grow {
    flex: 1;
    min-width: 0;
  }
  .avatar {
    width: 36px;
    height: 36px;
    border-radius: 50%;
    object-fit: cover;
  }
  .cover {
    width: 42px;
    height: 60px;
    border-radius: 6px;
    object-fit: cover;
    background: var(--surface-high);
  }
  .entry {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px;
    border-radius: 10px;
    background: var(--surface-high);
  }
  .tabs {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }
  .tabs button {
    border: 0;
    border-radius: 8px;
    padding: 6px 12px;
    background: var(--surface-high);
    color: var(--on-surface, var(--text));
    cursor: pointer;
  }
  .tabs button.on {
    background: var(--accent);
    color: #fff;
  }
  .switch {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  input:not([type="checkbox"]) {
    background: var(--surface-high);
    border: 0;
    border-radius: 8px;
    padding: 8px 10px;
  }
  .button.small {
    padding: 4px 10px;
    font-size: 13px;
  }
  .link {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    padding: 2px 0;
  }
  .small {
    font-size: 13px;
  }
  .error {
    color: var(--error);
  }
</style>
