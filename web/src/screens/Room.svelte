<script lang="ts">
  import { go, session as sessionStore } from "../app/context";
  import ChatPanel from "../components/ChatPanel.svelte";
  import Icon from "../components/Icon.svelte";
  import PeoplePanel from "../components/PeoplePanel.svelte";
  import PlaylistPanel from "../components/PlaylistPanel.svelte";
  import type { RoomState } from "../lib/syncplay/client";
  import * as ManagedRooms from "../lib/syncplay/managedRooms";

  /**
   * The Room tab: on a wide window three columns (the playlist, the chat at full height with its box at the bottom,
   * and the room), as on desktop; on a phone one at a time.
   */
  const session = $derived($sessionStore);
  let room = $state<RoomState | null>(null);
  let tab = $state<"playlist" | "chat" | "room">("playlist");

  $effect(() => {
    if (!session) return;
    return session.room.subscribe((r) => (room = r));
  });
</script>

<main class="room">
  {#if !room || room.status === "DISCONNECTED"}
    <section class="card empty">
      <h1>Not in a room</h1>
      <p class="dim">Join a room on a YarmiplayServerTV to watch together.</p>
      <button class="button" onclick={() => go("#/join")}><Icon name="login" size={20} />Join a room</button>
    </section>
  {:else}
    <header>
      <h1>{ManagedRooms.label(room.room)}</h1>
      <button class="button quiet" onclick={() => go("#/player")}><Icon name="play_circle" size={20} />Player</button>
    </header>
    <nav class="tabs" aria-label="Room sections">
      <button class:on={tab === "playlist"} onclick={() => (tab = "playlist")}><Icon name="queue_music" size={20} />Playlist</button>
      <button class:on={tab === "chat"} onclick={() => (tab = "chat")}><Icon name="forum" size={20} />Chat</button>
      <button class:on={tab === "room"} onclick={() => (tab = "room")}><Icon name="groups" size={20} />Room</button>
    </nav>
    <div class="columns">
      <section class="card col" class:show={tab === "playlist"} aria-label="Playlist">
        <h2>Playlist</h2>
        <PlaylistPanel />
      </section>
      <section class="card col" class:show={tab === "chat"} aria-label="Chat">
        <h2>Chat</h2>
        <ChatPanel />
      </section>
      <section class="card col" class:show={tab === "room"} aria-label="Room">
        <h2>Room</h2>
        <PeoplePanel />
      </section>
    </div>
  {/if}
</main>

<style>
  .room {
    height: 100%;
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 16px;
    min-height: 0;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
  }
  h1 {
    margin: 0;
    font-size: 22px;
    overflow-wrap: anywhere;
  }
  h2 {
    margin: 0 16px 8px;
    font-size: 16px;
  }
  .empty {
    max-width: 480px;
    margin: 32px auto;
    display: grid;
    gap: 10px;
    justify-items: start;
  }
  .empty h1,
  .empty p {
    margin: 0;
  }
  .columns {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: 1fr 1.2fr 1fr;
    gap: 12px;
  }
  .col {
    display: flex;
    flex-direction: column;
    min-height: 0;
    padding: 12px 0 0;
  }
  .tabs {
    display: none;
    gap: 4px;
  }
  .tabs button {
    flex: 1;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    background: var(--surface);
    border: 0;
    border-radius: 10px;
    padding: 8px;
    cursor: pointer;
  }
  .tabs button.on {
    background: var(--surface-high);
    color: var(--accent);
    font-weight: 600;
  }
  @media (max-width: 899px) {
    .tabs {
      display: flex;
    }
    .columns {
      grid-template-columns: 1fr;
    }
    .col {
      display: none;
    }
    .col.show {
      display: flex;
    }
  }
</style>
