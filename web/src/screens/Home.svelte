<script lang="ts">
  import { changeSettings, go, library, player as playerStore, session as sessionStore, settings, version } from "../app/context";
  import buildInfo from "virtual:build-info";
  import type { PlayerState } from "../lib/player/mediaPlayer";
  import type { RoomState } from "../lib/syncplay/client";
  import * as ManagedRooms from "../lib/syncplay/managedRooms";
  import { keepsFolders } from "../lib/local/library";
  import { sources } from "../app/media";
  import Icon from "../components/Icon.svelte";
  import MediaTile from "../components/MediaTile.svelte";
  import type { MediaItem, MediaSource } from "../lib/media";

  /** Each server's "Continue watching" and recently added, as on the apps' home screen. */
  let rows = $state<{ source: MediaSource; items: MediaItem[] }[]>([]);
  $effect(() => {
    const list = $sources;
    rows = [];
    list.forEach((s) => s.recent(12).then((items) => items.length && (rows = [...rows, { source: s, items }]), () => {}));
  });

  /** Home (MobileHome.kt for the web): a card for each way to watch, with their text in full. */
  const session = $derived($sessionStore);
  let room = $state<RoomState | null>(null);
  let playing = $state<PlayerState | null>(null);
  let fileCount = $state(library.files.length);
  let folderCount = $state(library.mediaFolders.length);
  let lastRoom = $derived($settings.savedRooms[0] ?? null);

  $effect(() => {
    if (!session) return;
    return session.room.subscribe((r) => (room = r));
  });
  $effect(() => {
    const p = $playerStore;
    if (!p) return;
    return p.subscribe((s) => (playing = s));
  });
  $effect(() =>
    library.subscribe(() => {
      fileCount = library.files.length;
      folderCount = library.mediaFolders.length;
    }),
  );

  const inRoom = $derived(room?.status === "CONNECTED");
  const ready = $derived(room?.users.filter((u) => u.isReady).length ?? 0);
  const whatsNew = $derived($settings.lastSeenVersion !== version ? buildInfo.releases.find((r) => r.version === version) : null);

</script>

<main class="home">
  <header>
    <img src="./favicon.svg" alt="" width="40" height="40" />
    <h1>YarmiplayTV</h1>
  </header>

  {#if whatsNew}
    <section class="card new">
      <h2>What's new in {version}</h2>
      <p>{whatsNew.text}</p>
      <button class="button quiet" onclick={() => changeSettings((s) => ({ ...s, lastSeenVersion: version }))}><Icon name="check" size={18} />Got it</button>
    </section>
  {/if}

  <div class="cards">
    <button class="card tile" onclick={() => go(inRoom ? "#/room" : "#/join")}>
      <span class="tile-icon"><Icon name="groups" size={22} /></span>
      <span class="label">{inRoom ? ManagedRooms.label(room!.room) : "Join a Syncplay room"}</span>
      <span class="dim">
        {#if inRoom}
          {room!.users.length} watching · {ready} ready
        {:else if room && room.status !== "DISCONNECTED"}
          Connecting…
        {:else if lastRoom}
          Last: {lastRoom.label || ManagedRooms.display(lastRoom.room)} on {lastRoom.host}
        {:else}
          Watch together on a YarmiplayServerTV
        {/if}
      </span>
    </button>

    {#if !inRoom && lastRoom && (!room || room.status === "DISCONNECTED")}
      <button
        class="card tile"
        onclick={() => {
          session?.join(
            { host: lastRoom!.host, port: lastRoom!.port, username: lastRoom!.username, room: lastRoom!.room, password: lastRoom!.password },
            { lanName: lastRoom!.lanName ?? null },
          );
          go("#/room");
        }}
      >
        <span class="tile-icon"><Icon name="meeting_room" size={22} /></span>
        <span class="label">Rejoin {lastRoom.label || ManagedRooms.display(lastRoom.room)}</span>
        <span class="dim">{lastRoom.host}:{lastRoom.port} as {lastRoom.username}</span>
      </button>
    {/if}

    {#if playing?.loaded}
      <button class="card tile" onclick={() => go("#/player")}>
        <span class="tile-icon"><Icon name="play_circle" size={22} /></span>
        <span class="label">Now playing</span>
        <span class="dim">{playing.name}</span>
      </button>
    {/if}

    <button class="card tile" onclick={() => go("#/files")}>
      <span class="tile-icon"><Icon name="video_file" size={22} /></span>
      <span class="label">Files on this device</span>
      <span class="dim">
        {folderCount ? `${folderCount} media folder${folderCount === 1 ? "" : "s"}, ${fileCount} videos` : "Open a video or add media folders"}
      </span>
    </button>

    <button class="card tile" onclick={() => go($sources.length ? "#/library" : "#/servers")}>
      <span class="tile-icon"><Icon name="video_library" size={22} /></span>
      <span class="label">{$sources.length ? "Libraries" : "Jellyfin and Plex"}</span>
      <span class="dim">{$sources.length ? $sources.map((x) => x.displayName).join(" · ") : "Add a server to browse it and play from it"}</span>
    </button>

    <button class="card tile" onclick={() => go("#/settings/media")}>
      <span class="tile-icon"><Icon name="folder" size={22} /></span>
      <span class="label">Media folders</span>
      <span class="dim">
        {keepsFolders() ? "Videos in them are found by themselves when a room plays them" : "Pick a folder for this visit; this browser can't keep it"}
      </span>
    </button>
  </div>

  {#each rows as row (row.source.key)}
    <section class="shelf">
      <h2>{row.source.displayName}</h2>
      <ul class="cards">
        {#each row.items as item (item.id)}<MediaTile {item} />{/each}
      </ul>
    </section>
  {/each}

  <p class="dim small">Version {version} · <a href="https://tv.yarmiplay.com/manual/" target="_blank" rel="noopener">Manual</a></p>
</main>

<style>
  .home {
    max-width: 960px;
    margin: 0 auto;
    padding: 16px;
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
    margin: 0 0 6px;
    font-size: 17px;
  }
  .new {
    border: 1px solid var(--accent);
    display: grid;
    gap: 8px;
    justify-items: start;
  }
  .new p {
    margin: 0;
  }
  .cards {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 12px;
  }
  .shelf .cards {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .tile {
    display: grid;
    gap: 4px;
    align-content: start;
    text-align: left;
    border: 0;
    cursor: pointer;
    min-height: 96px;
  }
  .tile-icon {
    width: 40px;
    height: 40px;
    border-radius: 12px;
    display: grid;
    place-items: center;
    margin-bottom: 6px;
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 14%, transparent);
  }
  .tile:hover {
    background: var(--surface-high);
  }
  .label {
    font-weight: 600;
    font-size: 17px;
    overflow-wrap: anywhere;
  }
  .tile .dim {
    overflow-wrap: anywhere;
  }
  .small {
    font-size: 13px;
  }
</style>
