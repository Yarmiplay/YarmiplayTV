<script lang="ts">
  import { changeSettings, go, library, player as playerStore, session as sessionStore, settings, version } from "../app/context";
  import buildInfo from "virtual:build-info";
  import type { PlayerState } from "../lib/player/mediaPlayer";
  import type { RoomState } from "../lib/syncplay/client";
  import * as ManagedRooms from "../lib/syncplay/managedRooms";
  import { keepsFolders } from "../lib/local/library";

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

  function openVideo(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    (event.target as HTMLInputElement).value = "";
    if (!file || !session) return;
    void session.playLocal(file, inRoom);
    go("#/player");
  }
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
      <button class="button quiet" onclick={() => changeSettings((s) => ({ ...s, lastSeenVersion: version }))}>Got it</button>
    </section>
  {/if}

  <div class="cards">
    <button class="card tile" onclick={() => go(inRoom ? "#/room" : "#/join")}>
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
        <span class="label">Rejoin {lastRoom.label || ManagedRooms.display(lastRoom.room)}</span>
        <span class="dim">{lastRoom.host}:{lastRoom.port} as {lastRoom.username}</span>
      </button>
    {/if}

    {#if playing?.loaded}
      <button class="card tile" onclick={() => go("#/player")}>
        <span class="label">Now playing</span>
        <span class="dim">{playing.name}</span>
      </button>
    {/if}

    <label class="card tile">
      <span class="label">Files on this device</span>
      <span class="dim">
        Open a video{folderCount ? ` · ${folderCount} media folder${folderCount === 1 ? "" : "s"}, ${fileCount} videos` : ""}
      </span>
      <input type="file" accept="video/*,.mkv,.avi,.ts,.m2ts,.webm" onchange={openVideo} hidden />
    </label>

    <button class="card tile" onclick={() => go("#/settings/media")}>
      <span class="label">Media folders</span>
      <span class="dim">
        {keepsFolders() ? "Videos in them are found by themselves when a room plays them" : "Pick a folder for this visit; this browser can't keep it"}
      </span>
    </button>
  </div>

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
  .tile {
    display: grid;
    gap: 4px;
    align-content: start;
    text-align: left;
    border: 0;
    cursor: pointer;
    min-height: 96px;
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
