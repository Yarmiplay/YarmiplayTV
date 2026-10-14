<script lang="ts">
  import { changeSettings, settings } from "../app/context";
  import { addJellyfin, addPlex, jellyfinClient, plexClient, remove, sources } from "../app/media";
  import type { PlexServer } from "../lib/media";

  /**
   * Media servers (MobileConnect.kt's Jellyfin and Plex sign-ins, and Settings > Media servers): Jellyfin by Quick
   * Connect or name and password, Plex by a code typed at plex.tv/link. A page on https can only reach servers over
   * https: a Jellyfin behind HTTPS, one the host shares through YarmiplayServerTV, or Plex's plex.direct addresses.
   */
  let adding = $state<"jellyfin" | "plex" | null>(null);
  let error = $state<string | null>(null);
  let busy = $state(false);

  // Jellyfin
  let url = $state("");
  let quickConnect = $state<boolean | null>(null);
  let code = $state<string | null>(null);
  let user = $state("");
  let password = $state("");
  let abort: AbortController | null = null;

  // Plex
  let plexCode = $state<string | null>(null);
  let plexServers = $state<PlexServer[] | null>(null);
  let plexAccount: { accountToken: string; userName: string } | null = null;

  function reset() {
    abort?.abort();
    abort = null;
    adding = null;
    error = null;
    busy = false;
    code = null;
    quickConnect = null;
    plexCode = null;
    plexServers = null;
  }

  async function checkJellyfin() {
    error = null;
    busy = true;
    try {
      await jellyfinClient.publicInfo(url);
      quickConnect = await jellyfinClient.quickConnectEnabled(url);
    } catch (e) {
      error = (e as Error).message;
    } finally {
      busy = false;
    }
  }

  async function startQuickConnect() {
    error = null;
    abort = new AbortController();
    try {
      const session = await jellyfinClient.quickConnect(url, { onCode: (c) => (code = c), signal: abort.signal });
      addJellyfin(session);
      reset();
    } catch (e) {
      if (!abort?.signal.aborted) error = (e as Error).message;
      code = null;
    }
  }

  async function signIn(event: SubmitEvent) {
    event.preventDefault();
    error = null;
    busy = true;
    try {
      addJellyfin(await jellyfinClient.login(url, user, password));
      reset();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      busy = false;
    }
  }

  async function linkPlex() {
    adding = "plex";
    error = null;
    abort = new AbortController();
    try {
      plexAccount = await plexClient.link({ onCode: (c) => (plexCode = c), signal: abort.signal });
      plexServers = await plexClient.servers(plexAccount.accountToken);
    } catch (e) {
      if (!abort?.signal.aborted) error = (e as Error).message;
    }
  }

  async function pickPlex(server: PlexServer) {
    if (!plexAccount) return;
    error = null;
    busy = true;
    try {
      addPlex(await plexClient.connect(server, plexAccount.accountToken, plexAccount.userName));
      reset();
    } catch (e) {
      error = (e as Error).message;
    } finally {
      busy = false;
    }
  }
</script>

<main class="servers">
  <h1>Media servers</h1>

  <section class="card group">
    {#if $sources.length === 0}
      <p class="dim">None yet. Their libraries show side by side, searches cover all of them, and a room's entries are found on them.</p>
    {/if}
    {#each $sources as s (s.key)}
      <div class="row">
        <span class="grow"><b>{s.displayName}</b> <span class="dim small">{s.kind}</span></span>
        <button class="link" onclick={() => remove(s)}>Sign out</button>
      </div>
    {/each}
    {#if $sources.length > 1}
      <label>
        Preferred server (streams a room's file when several have it)
        <select value={$settings.preferredServer} onchange={(e) => changeSettings((x) => ({ ...x, preferredServer: (e.target as HTMLSelectElement).value }))}>
          <option value="">The first that has it</option>
          {#each $sources as s (s.key)}<option value={s.key}>{s.displayName}</option>{/each}
        </select>
      </label>
    {/if}
    <label class="switch">
      <input type="checkbox" checked={$settings.reportPlayback} onchange={(e) => changeSettings((x) => ({ ...x, reportPlayback: (e.target as HTMLInputElement).checked }))} />
      Report progress to your media servers (watched at 90%; the room's position always wins)
    </label>
    {#if !adding}
      <div class="row">
        <button class="button" onclick={() => (adding = "jellyfin")}>Add Jellyfin</button>
        <button class="button" onclick={linkPlex}>Link Plex</button>
      </div>
    {/if}
  </section>

  {#if adding === "jellyfin"}
    <section class="card group">
      <h2>Jellyfin</h2>
      {#if quickConnect === null}
        <form
          onsubmit={(e) => {
            e.preventDefault();
            void checkJellyfin();
          }}
        >
          <label>
            Server address
            <input bind:value={url} placeholder="https://jellyfin.example.com" autocomplete="url" spellcheck="false" required />
          </label>
          <p class="dim small">From this page the server has to be reachable over https.</p>
          <div class="row">
            <button class="button" type="submit" disabled={busy || !url.trim()}>Next</button>
            <button class="button quiet" type="button" onclick={reset}>Cancel</button>
          </div>
        </form>
      {:else}
        {#if quickConnect}
          {#if code}
            <p>In another Jellyfin app, go to your profile, then Quick Connect, and enter:</p>
            <p class="code mono" dir="ltr">{code}</p>
          {:else}
            <button class="button" onclick={startQuickConnect}>Sign in with Quick Connect</button>
          {/if}
          <p class="dim small">Or with your user name and password:</p>
        {/if}
        <form onsubmit={signIn}>
          <label>User name <input bind:value={user} autocomplete="username" required /></label>
          <label>Password <input bind:value={password} type="password" autocomplete="current-password" /></label>
          <div class="row">
            <button class="button" type="submit" disabled={busy || !user.trim()}>Sign in</button>
            <button class="button quiet" type="button" onclick={reset}>Cancel</button>
          </div>
        </form>
      {/if}
    </section>
  {/if}

  {#if adding === "plex"}
    <section class="card group">
      <h2>Plex</h2>
      {#if plexServers}
        <p>Pick the server to use:</p>
        {#each plexServers as server (server.machineId)}
          <button class="button quiet" disabled={busy} onclick={() => pickPlex(server)}>{server.name}{server.owned ? "" : " (shared with you)"}</button>
        {/each}
        {#if plexServers.length === 0}<p class="dim">This account has no servers.</p>{/if}
      {:else if plexCode}
        <p>Go to <a href="https://plex.tv/link" target="_blank" rel="noopener">plex.tv/link</a> on your phone or computer and enter:</p>
        <p class="code mono" dir="ltr">{plexCode}</p>
      {:else}
        <p class="dim">Asking Plex for a code…</p>
      {/if}
      <div class="row"><button class="button quiet" onclick={reset}>Cancel</button></div>
    </section>
  {/if}

  {#if error}<p class="error" role="alert">{error}</p>{/if}
</main>

<style>
  .servers {
    max-width: 720px;
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
  .group,
  form {
    display: grid;
    gap: 12px;
  }
  p {
    margin: 0;
  }
  label {
    display: grid;
    gap: 4px;
  }
  label.switch {
    display: flex;
    gap: 10px;
    align-items: flex-start;
  }
  input:not([type="checkbox"]),
  select {
    background: var(--surface-high);
    border: 0;
    border-radius: 10px;
    padding: 10px 12px;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }
  .grow {
    flex: 1;
  }
  .small {
    font-size: 13px;
  }
  .code {
    justify-self: start;
    font-size: 40px;
    font-weight: 700;
    letter-spacing: 4px;
    color: var(--accent);
    background: var(--surface-high);
    border-radius: 10px;
    padding: 8px 16px;
  }
  .link {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
  }
  .error {
    color: var(--error);
  }
</style>
