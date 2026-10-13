<script lang="ts">
  import { onMount } from "svelte";
  import buildInfo from "virtual:build-info";
  import { THEMES, applyTheme, followSystem, themeOf, type ThemeKey } from "./lib/ui/theme";
  import PlayerLab from "./screens/PlayerLab.svelte";

  let route = $state(location.hash);
  let theme = $state<ThemeKey>(themeOf(readTheme()));

  function readTheme(): string | null {
    try {
      return localStorage.getItem("ytv:app_theme");
    } catch {
      return null;
    }
  }

  function chooseTheme(key: ThemeKey) {
    theme = key;
    try {
      localStorage.setItem("ytv:app_theme", key);
    } catch {
      // Private windows may refuse storage; the theme still applies until the page closes.
    }
    applyTheme(key);
  }

  onMount(() => {
    const onHash = () => (route = location.hash);
    addEventListener("hashchange", onHash);
    const stop = followSystem(() => theme);
    return () => {
      removeEventListener("hashchange", onHash);
      stop();
    };
  });
</script>

{#if route.startsWith("#/lab/player")}
  <PlayerLab />
{:else}
  <main class="shell">
    <header>
      <img src="./favicon.svg" alt="" width="40" height="40" />
      <h1>YarmiplayTV</h1>
    </header>
    <section class="card">
      <p>Watch together in your browser, with nothing to install. This page is being built.</p>
      <p class="dim">Version {buildInfo.version}</p>
      <p><a href="#/lab/player">Player lab</a>: try the browser player on a file from this device or a link.</p>
    </section>
    <section class="card">
      <h2>Appearance</h2>
      <div class="themes">
        {#each THEMES as t (t.key)}
          <label>
            <input type="radio" name="theme" checked={theme === t.key} onchange={() => chooseTheme(t.key)} />
            <span>{t.label}</span>
            <span class="dim">{t.description}</span>
          </label>
        {/each}
      </div>
    </section>
  </main>
{/if}

<style>
  .shell {
    max-width: 720px;
    margin: 0 auto;
    padding: 24px 16px;
    display: grid;
    gap: 16px;
  }
  header {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  h1 {
    margin: 0;
    font-size: 24px;
  }
  h2 {
    margin: 0 0 8px;
    font-size: 18px;
  }
  .themes {
    display: grid;
    gap: 8px;
  }
  .themes label {
    display: grid;
    grid-template-columns: auto 1fr;
    column-gap: 10px;
    cursor: pointer;
  }
  .themes .dim {
    grid-column: 2;
    font-size: 14px;
  }
</style>
