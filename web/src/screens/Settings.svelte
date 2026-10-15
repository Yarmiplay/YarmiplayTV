<script lang="ts">
  import buildInfo from "virtual:build-info";
  import { changeSettings, go, library, route, settings, version } from "../app/context";
  import { keepsFolders, type MediaFolder } from "../lib/local/library";
  import { UnpauseMode } from "../lib/syncplay/models";
  import { THEME_NOTE, THEMES } from "../lib/ui/theme";
  import Icon from "../components/Icon.svelte";

  /**
   * Settings in categories (MobileSettings.kt): a list of pages on phones, the categories beside their settings on
   * wide windows. What browsers can't do (download folders, hardware decoding, updates) isn't here.
   */
  const CATEGORIES = [
    { key: "appearance", label: "Appearance", icon: "palette" },
    { key: "media", label: "Media", icon: "video_library" },
    { key: "myanimelist", label: "MyAnimeList", icon: "bookmarks" },
    { key: "syncing", label: "Syncing", icon: "sync" },
    { key: "playback", label: "Playback", icon: "tune" },
    { key: "whatsnew", label: "What's new", icon: "new_releases" },
    { key: "about", label: "About", icon: "info" },
  ] as const;

  const current = $derived($route.split("/")[2] ?? "");
  let wide = $state(matchMedia("(min-width: 840px)").matches);
  $effect(() => {
    const query = matchMedia("(min-width: 840px)");
    const changed = () => (wide = query.matches);
    query.addEventListener("change", changed);
    return () => query.removeEventListener("change", changed);
  });
  const shown = $derived(current || (wide ? "appearance" : ""));

  let folders: MediaFolder[] = $state(library.mediaFolders);
  $effect(() => library.subscribe(() => (folders = [...library.mediaFolders])));

  const set = changeSettings;
  const s = $derived($settings);

  async function addFolder() {
    try {
      await library.addFolder();
    } catch {
      // Picker closed.
    }
  }

  function folderFiles(event: Event) {
    const files = [...((event.target as HTMLInputElement).files ?? [])];
    (event.target as HTMLInputElement).value = "";
    if (files.length) void library.addFolderFiles(files);
  }
</script>

<main class="settings" class:wide>
  {#if wide || !shown}
    <nav aria-label="Settings categories">
      <h1>Settings</h1>
      {#each CATEGORIES as c (c.key)}
        <a href="#/settings/{c.key}" class:on={shown === c.key}><Icon name={c.icon} size={22} /><span class="grow">{c.label}</span>{#if !wide}<Icon name="chevron_right" size={20} />{/if}</a>
      {/each}
    </nav>
  {/if}

  {#if shown}
    <section class="page">
      {#if !wide}<button class="back" onclick={() => go("#/settings")}><Icon name="arrow_back" size={20} />Settings</button>{/if}

      {#if shown === "appearance"}
        <h2>Appearance</h2>
        <div class="card group">
          {#each THEMES as t (t.key)}
            <label class="radio">
              <input type="radio" name="theme" checked={s.theme === t.key} onchange={() => set((x) => ({ ...x, theme: t.key }))} />
              <span><b>{t.label}</b><br /><span class="dim">{t.description}</span></span>
            </label>
          {/each}
          <p class="dim small">{THEME_NOTE}</p>
        </div>
      {:else if shown === "media"}
        <h2>Media</h2>
        <div class="card group">
          <h3>Media servers</h3>
          <p class="dim small">Jellyfin and Plex: browse and search them, and a room's entries are found on them.</p>
          <a class="button quiet" href="#/servers"><Icon name="dns" size={18} />Media servers</a>
          <label class="switch">
            <input type="checkbox" checked={s.addSharedServers} onchange={(e) => set((x) => ({ ...x, addSharedServers: (e.target as HTMLInputElement).checked }))} />
            <span>Add media servers shared by Syncplay hosts<br /><span class="dim small">When a YarmiplayServerTV host shares their Jellyfin, sign in to it automatically</span></span>
          </label>
        </div>
        <div class="card group">
          <h3>Media folders</h3>
          <p class="dim small">Videos in them are found by their names when a room plays them, like the desktop Syncplay client's media directories.</p>
          {#each folders as f, i (f.name + i)}
            <div class="row">
              <span class="grow">{f.name}{f.readable ? "" : " (needs your permission again)"}</span>
              <button class="link" onclick={() => library.removeFolder(i)} aria-label="Remove {f.name}" title="Remove"><Icon name="delete" size={20} /></button>
            </div>
          {/each}
          {#if folders.some((f) => !f.readable)}
            <button class="button quiet" onclick={() => library.allowAgain()}><Icon name="lock" size={18} />Allow access again</button>
          {/if}
          {#if keepsFolders()}
            <button class="button quiet" onclick={addFolder}><Icon name="create_new_folder" size={18} />Add a media folder</button>
          {:else}
            <label class="button quiet">
              <Icon name="create_new_folder" size={18} />Add a media folder (for this visit)
              <input type="file" webkitdirectory multiple onchange={folderFiles} hidden />
            </label>
            <p class="dim small">This browser can't keep folders between visits; Chrome and Edge can.</p>
          {/if}
        </div>
        <div class="card group">
          <label class="switch">
            <input type="checkbox" checked={s.shareFiles} onchange={(e) => set((x) => ({ ...x, shareFiles: (e.target as HTMLInputElement).checked }))} />
            Share my files with the room (YarmiplayServerTV's file relay)
          </label>
        </div>
      {:else if shown === "syncing"}
        <h2>Syncing</h2>
        <div class="card group">
          <label>
            When you unpause
            <select
              value={s.sync.unpauseMode}
              onchange={(e) => set((x) => ({ ...x, sync: { ...x.sync, unpauseMode: (e.target as HTMLSelectElement).value as UnpauseMode } }))}
            >
              <option value={UnpauseMode.IF_OTHERS_READY}>Play if everyone else is ready</option>
              <option value={UnpauseMode.IF_ALREADY_READY}>Mark me ready first, play the second time</option>
              <option value={UnpauseMode.ALWAYS}>Always play</option>
            </select>
          </label>
          <label class="switch"><input type="checkbox" checked={s.sync.rewindOnDesync} onchange={(e) => set((x) => ({ ...x, sync: { ...x.sync, rewindOnDesync: (e.target as HTMLInputElement).checked } }))} /> Rewind when ahead of the room</label>
          <label class="switch"><input type="checkbox" checked={s.sync.slowOnDesync} onchange={(e) => set((x) => ({ ...x, sync: { ...x.sync, slowOnDesync: (e.target as HTMLInputElement).checked } }))} /> Slow down to 0.95× to catch up</label>
          <label class="switch"><input type="checkbox" checked={s.sync.readyAtStart} onchange={(e) => set((x) => ({ ...x, sync: { ...x.sync, readyAtStart: (e.target as HTMLInputElement).checked } }))} /> Ready when joining</label>
          <label class="switch"><input type="checkbox" checked={s.autoReady} onchange={(e) => set((x) => ({ ...x, autoReady: (e.target as HTMLInputElement).checked }))} /> Ready once the room's entry has loaded</label>
          <label class="switch"><input type="checkbox" checked={s.autosavePlaylists} onchange={(e) => set((x) => ({ ...x, autosavePlaylists: (e.target as HTMLInputElement).checked }))} /> Remember room playlists</label>
          <label class="switch"><input type="checkbox" checked={s.showChat} onchange={(e) => set((x) => ({ ...x, showChat: (e.target as HTMLInputElement).checked }))} /> Show chat</label>
          {#if s.showChat}
            <label class="switch"><input type="checkbox" checked={s.chatOnVideo} onchange={(e) => set((x) => ({ ...x, chatOnVideo: (e.target as HTMLInputElement).checked }))} /> Chat on the video</label>
          {/if}
          <label>
            Device name (what the host sees)
            <input value={s.deviceName} maxlength="60" onchange={(e) => set((x) => ({ ...x, deviceName: (e.target as HTMLInputElement).value.trim() || x.deviceName }))} />
          </label>
        </div>
      {:else if shown === "playback"}
        <h2>Playback</h2>
        <div class="card group">
          <label>
            Seek step
            <select value={String(s.playback.seekStepSeconds)} onchange={(e) => set((x) => ({ ...x, playback: { ...x.playback, seekStepSeconds: Number((e.target as HTMLSelectElement).value) } }))}>
              {#each [5, 10, 15, 30] as step (step)}<option value={String(step)}>{step} seconds</option>{/each}
            </select>
          </label>
          <label class="switch"><input type="checkbox" checked={s.playback.playerLabels} onchange={(e) => set((x) => ({ ...x, playback: { ...x.playback, playerLabels: (e.target as HTMLInputElement).checked } }))} /> Show names next to the player's buttons</label>
          <label class="switch"><input type="checkbox" checked={s.playback.stayConnectedMinimized} onchange={(e) => set((x) => ({ ...x, playback: { ...x.playback, stayConnectedMinimized: (e.target as HTMLInputElement).checked } }))} /> Stay connected when minimized</label>
          <h3>Openings and endings</h3>
          <label class="switch"><input type="checkbox" checked={s.playback.autoSkipAlone} onchange={(e) => set((x) => ({ ...x, playback: { ...x.playback, autoSkipAlone: (e.target as HTMLInputElement).checked } }))} /> Skip them by themselves when watching alone (in a room, the room decides)</label>
          <label class="switch"><input type="checkbox" checked={s.playback.showSkipButton} onchange={(e) => set((x) => ({ ...x, playback: { ...x.playback, showSkipButton: (e.target as HTMLInputElement).checked } }))} /> Show a Skip button for a few seconds as one starts</label>
          <label class="switch"><input type="checkbox" checked={s.playback.detectSkips} onchange={(e) => set((x) => ({ ...x, playback: { ...x.playback, detectSkips: (e.target as HTMLInputElement).checked } }))} /> Find them by listening when nothing else knows them</label>
          <label class="switch"><input type="checkbox" checked={s.playback.detectOnAnyNetwork} disabled={!s.playback.detectSkips} onchange={(e) => set((x) => ({ ...x, playback: { ...x.playback, detectOnAnyNetwork: (e.target as HTMLInputElement).checked } }))} /> Also listen to server episodes on mobile data</label>
          <label>
            Audio languages, in order
            <input value={s.playback.audioLanguages} onchange={(e) => set((x) => ({ ...x, playback: { ...x.playback, audioLanguages: (e.target as HTMLInputElement).value } }))} />
          </label>
          <label>
            Subtitle languages, in order
            <input value={s.playback.subtitleLanguages} onchange={(e) => set((x) => ({ ...x, playback: { ...x.playback, subtitleLanguages: (e.target as HTMLInputElement).value } }))} />
          </label>
        </div>
      {:else if shown === "myanimelist"}
        <h2>MyAnimeList</h2>
        <div class="card group">
          <p>Keep your MyAnimeList list up to date as you watch, and find any show.</p>
          <a class="button quiet" href="#/mal"><Icon name="bookmarks" size={18} />MyAnimeList</a>
        </div>
      {:else if shown === "whatsnew"}
        <h2>What's new</h2>
        <div class="card group">
          {#each buildInfo.releases as r (r.version)}
            <div class="release">
              <h3>{r.version} <span class="dim small">{r.date}</span></h3>
              <p>{r.text}</p>
            </div>
          {/each}
        </div>
      {:else if shown === "about"}
        <h2>About</h2>
        <div class="card group">
          <p>YarmiplayTV {version} in the browser</p>
          <div class="links">
            <a href="https://tv.yarmiplay.com/manual/" target="_blank" rel="noopener"><Icon name="menu_book" />User manual</a>
            <a href="https://tv.yarmiplay.com/changelog/" target="_blank" rel="noopener"><Icon name="new_releases" />All patch notes</a>
            <a href="https://tv.yarmiplay.com/" target="_blank" rel="noopener"><Icon name="download" />Website and downloads</a>
            <a href="https://tv.yarmiplay.com/privacy/" target="_blank" rel="noopener"><Icon name="shield" />Privacy policy</a>
            <a href="https://tv.yarmiplay.com/terms/" target="_blank" rel="noopener"><Icon name="description" />Community rules</a>
            <a href="https://github.com/Yarmiplay/YarmiplayTV" target="_blank" rel="noopener"><Icon name="code" />Source code</a>
            <a href="https://github.com/Yarmiplay/YarmiplayTV/issues" target="_blank" rel="noopener"><Icon name="bug_report" />Report a problem</a>
            <a href="https://github.com/Yarmiplay/YarmiplayServerTV#readme" target="_blank" rel="noopener"><Icon name="dns" />YarmiplayServerTV</a>
          </div>
          <h3>What's new</h3>
          {#each buildInfo.releases.slice(0, 5) as r (r.version)}
            <p><b>{r.version}</b> <span class="dim small">{r.date}</span><br />{r.text}</p>
          {/each}
          <p class="dim small">
            Plays video with FFmpeg (libav.js, LGPL) and draws subtitles with libass (JASSUB, MIT).
          </p>
        </div>
      {/if}
    </section>
  {/if}
</main>

<style>
  .settings {
    max-width: 960px;
    margin: 0 auto;
    padding: 16px;
    display: grid;
    gap: 16px;
  }
  .settings.wide {
    grid-template-columns: 220px 1fr;
    align-items: start;
  }
  nav {
    display: grid;
    gap: 4px;
  }
  nav h1 {
    margin: 0 0 8px;
    font-size: 24px;
  }
  nav a {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
    border-radius: 10px;
    color: var(--text);
    text-decoration: none;
    background: var(--surface);
  }
  nav a.on {
    background: var(--surface-high);
    color: var(--accent);
    font-weight: 600;
  }
  .page {
    display: grid;
    gap: 12px;
  }
  h2 {
    margin: 0;
    font-size: 20px;
  }
  h3 {
    margin: 4px 0 0;
    font-size: 16px;
  }
  .group {
    display: grid;
    gap: 12px;
  }
  .group p {
    margin: 0;
  }
  label {
    display: grid;
    gap: 4px;
  }
  label.switch,
  label.radio {
    display: flex;
    gap: 10px;
    align-items: flex-start;
  }
  select,
  input:not([type="checkbox"]):not([type="radio"]) {
    background: var(--surface-high);
    border: 0;
    border-radius: 10px;
    padding: 8px 10px;
  }
  .row {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .grow {
    flex: 1;
    overflow-wrap: anywhere;
  }
  .small {
    font-size: 13px;
  }
  .link,
  .back {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    padding: 4px;
    justify-self: start;
  }
  .links {
    display: grid;
    gap: 6px;
  }
  .links a {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    justify-self: start;
  }
</style>
