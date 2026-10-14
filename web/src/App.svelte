<script lang="ts">
  import { onMount } from "svelte";
  import buildInfo from "virtual:build-info";
  import { go, route, settings } from "./app/context";
  import DeviceAccess from "./components/DeviceAccess.svelte";
  import Icon from "./components/Icon.svelte";
  import PlayerView from "./components/PlayerView.svelte";
  import { applyTheme, followSystem } from "./lib/ui/theme";
  import { newVersion, watchForUpdates } from "./lib/ui/updates";
  import Files from "./screens/Files.svelte";
  import Home from "./screens/Home.svelte";
  import Library from "./screens/Library.svelte";
  import Mal from "./screens/Mal.svelte";
  import "./app/watching";
  import Search from "./screens/Search.svelte";
  import Servers from "./screens/Servers.svelte";
  import Join from "./screens/Join.svelte";
  import PlayerLab from "./screens/PlayerLab.svelte";
  import Room from "./screens/Room.svelte";
  import Settings from "./screens/Settings.svelte";

  /**
   * The app's frame (MobileRoot.kt): Home, Player, Room and Settings, in a side list on wide windows and as tabs at
   * the bottom on phones. The player stays mounted whichever section shows, so the video keeps playing.
   */
  const SECTIONS = [
    { key: "home", label: "Home", href: "#/", icon: "home" },
    { key: "player", label: "Player", href: "#/player", icon: "play_circle" },
    { key: "search", label: "Search", href: "#/search", icon: "search" },
    { key: "room", label: "Room", href: "#/room", icon: "groups" },
    { key: "settings", label: "Settings", href: "#/settings", icon: "settings" },
  ] as const;

  const section = $derived.by(() => {
    const r = $route;
    if (r.includes("join=") || r.startsWith("#/join")) return "join";
    if (r.startsWith("#/lab/player")) return "lab";
    return r.replace(/^#\//, "").split("/")[0] || "home";
  });

  onMount(() => {
    applyTheme($settings.theme);
    const stops = [followSystem(() => $settings.theme), watchForUpdates(buildInfo)];
    return () => stops.forEach((stop) => stop());
  });
</script>

{#if section === "lab"}
  <PlayerLab />
{:else}
  <div class="frame">
    <nav class="sections" aria-label="Sections">
      <a class="brand" href="#/" aria-label="YarmiplayTV home"><img src="./favicon.svg" alt="" width="32" height="32" /><span>YarmiplayTV</span></a>
      {#each SECTIONS as s (s.key)}
        <a href={s.href} class:on={section === s.key || (s.key === "room" && section === "join")} aria-current={section === s.key ? "page" : undefined}
          ><Icon name={s.icon} size={22} /><span>{s.label}</span></a
        >
      {/each}
    </nav>
    <div class="content" class:full={section === "player"}>
      {#if $newVersion}
        <div class="update" role="status">
          <span>YarmiplayTV {$newVersion} is out.</span>
          <button class="button small" onclick={() => location.reload()}><Icon name="refresh" size={18} />Reload</button>
          <button class="link" onclick={() => newVersion.set(null)}>Later</button>
        </div>
      {/if}
      <PlayerView visible={section === "player"} />
      {#if section === "home"}
        <Home />
      {:else if section === "join"}
        <Join />
      {:else if section === "room"}
        <Room />
      {:else if section === "settings"}
        <Settings />
      {:else if section === "files"}
        <Files />
      {:else if section === "servers"}
        <Servers />
      {:else if section === "library"}
        <Library />
      {:else if section === "search"}
        <Search />
      {:else if section === "mal"}
        <Mal />
      {:else if section !== "player"}
        <main class="missing">
          <p>There's nothing here.</p>
          <button class="button" onclick={() => go("#/")}>Home</button>
        </main>
      {/if}
    </div>
  </div>
  <DeviceAccess />
{/if}

<style>
  .update {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    padding: 10px 16px;
    background: var(--surface-high);
  }
  .update .small {
    padding: 6px 14px;
  }
  .update .link {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
  }
  .frame {
    height: 100dvh;
    display: grid;
    grid-template-columns: 200px 1fr;
  }
  .sections {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 16px 12px;
    background: var(--surface);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 12px;
    padding: 0 4px;
    color: var(--text);
    font-weight: 700;
    font-size: 18px;
    text-decoration: none;
  }
  .sections a:not(.brand) {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
    border-radius: 10px;
    color: var(--text);
    text-decoration: none;
  }
  .sections a:not(.brand, .on) :global(.icon) {
    color: var(--text-dim);
  }
  .sections a.on {
    background: var(--surface-high);
    color: var(--accent);
    font-weight: 600;
  }
  .content {
    min-width: 0;
    min-height: 0;
    overflow: auto;
  }
  .content.full {
    overflow: hidden;
  }
  .missing {
    padding: 32px 16px;
    display: grid;
    gap: 12px;
    justify-items: start;
  }
  @media (max-width: 699px) {
    .frame {
      grid-template-columns: 1fr;
      grid-template-rows: 1fr auto;
    }
    .sections {
      order: 2;
      flex-direction: row;
      justify-content: space-around;
      padding: 6px 8px calc(6px + env(safe-area-inset-bottom));
    }
    .brand {
      display: none;
    }
    .sections a:not(.brand) {
      flex: 1;
      flex-direction: column;
      gap: 2px;
      text-align: center;
      padding: 6px 4px;
      font-size: 12px;
    }
  }
</style>
