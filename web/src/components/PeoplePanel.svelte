<script lang="ts">
  import { go, session as sessionStore } from "../app/context";
  import { followsOperators, hasManagedRooms, inManagedRoom, isOperator, type RoomState, type RoomUser } from "../lib/syncplay/client";
  import * as FileMatch from "../lib/syncplay/fileMatch";
  import * as ManagedRooms from "../lib/syncplay/managedRooms";
  import { formatTime } from "../lib/syncplay/models";
  import Icon from "./Icon.svelte";

  /**
   * The room (MobilePanels.kt's people and room parts): who's here, ready or not, and what they play, with a warning
   * when their file differs from ours; switching rooms, managed rooms, and leaving.
   */
  const session = $derived($sessionStore);
  let room = $state<RoomState | null>(null);
  let newRoom = $state("");
  let managedName = $state("");
  let operatorPassword = $state("");
  let copied = $state<string | null>(null);

  $effect(() => {
    if (!session) return;
    return session.room.subscribe((r) => (room = r));
  });

  const me = $derived(room?.users.find((u) => u.name === room?.username) ?? null);
  const operator = $derived(room ? isOperator(room) : false);

  function differences(u: RoomUser): string | null {
    if (!me?.file || !u.file || u.name === me.name) return null;
    const d = FileMatch.differences(me.file, u.file);
    if (d.size === 0) return null;
    const what = [...d].map((x) => ({ NAME: "name", SIZE: "size", DURATION: "length" })[x]);
    return `Different ${what.join(" and ")} from yours`;
  }

  async function copy(text: string) {
    await navigator.clipboard.writeText(text).catch(() => {});
    copied = text;
    setTimeout(() => (copied = null), 1500);
  }

  const savedPassword = $derived(room && operator ? session?.savedOperatorPassword(room.room) : null);
</script>

<div class="people">
  {#if !room || room.status === "DISCONNECTED"}
    <p class="dim">Not in a room.</p>
    <button class="button" onclick={() => go("#/join")}><Icon name="login" size={20} />Join a room</button>
  {:else}
    <section>
      <h3>{ManagedRooms.label(room.room)}</h3>
      <p class="dim small">
        {room.status === "CONNECTED" ? "Connected" : room.status === "RECONNECTING" ? "Reconnecting…" : "Connecting…"}
        {#if room.route?.atHome}· at home via {room.route.host}{/if}
        {#if room.rttMillis}· {Math.round(room.rttMillis)} ms{/if}
      </p>
      {#if room.yarmiplay.device.kind === "pending"}
        <p class="notice">
          Waiting for the host to let this device in. Check that the host sees the same code:
          <b class="mono">{room.yarmiplay.device.fingerprint}</b>
        </p>
      {/if}
    </section>

    <ul class="users">
      {#each room.users as u (u.name)}
        <li>
          <span class="dot" class:ready={u.isReady === true} class:unknown={u.isReady === null}></span>
          <div class="who">
            <b>{u.name}{u.name === room.username ? " (you)" : ""}</b>
            {#if u.isController}<span class="dim small">· operator</span>{/if}
            {#if u.file}
              <span class="dim small file">{u.file.name} ({formatTime(u.file.duration)})</span>
            {:else}
              <span class="dim small">No file</span>
            {/if}
            {#if differences(u)}<span class="warn small">{differences(u)}</span>{/if}
          </div>
          {#if operator && u.name !== room.username}
            <button class="link" onclick={() => session?.setOthersReady(u.name, u.isReady !== true)}>
              Mark {u.isReady === true ? "not ready" : "ready"}
            </button>
          {/if}
        </li>
      {/each}
    </ul>

    {@const autoSkip = session?.roomAutoSkip()}
    {#if autoSkip?.inRoom}
      <section>
        <label class="switch">
          <input type="checkbox" checked={autoSkip.on} disabled={!autoSkip.canChange} onchange={(e) => session?.setRoomAutoSkip((e.target as HTMLInputElement).checked)} />
          Skip openings and endings{autoSkip.shared ? " for everyone in the room" : ""}
        </label>
        {#if autoSkip.shared && autoSkip.setBy}<p class="dim small">Last changed by {autoSkip.setBy}</p>{/if}
      </section>
    {/if}

    <section>
      <button class="button" onclick={() => session?.toggleReady()} disabled={room.status !== "CONNECTED"}>
        <Icon name={room.isReady ? "block" : "check_circle"} size={20} />{room.isReady ? "I'm not ready" : "I'm ready"}
      </button>
    </section>

    <section class="form">
      <h4>Switch rooms</h4>
      <form
        onsubmit={(e) => {
          e.preventDefault();
          if (newRoom.trim()) session?.changeRoom(newRoom);
          newRoom = "";
        }}
      >
        <input bind:value={newRoom} placeholder="Room name" aria-label="Room to switch to" />
        <button class="button quiet" type="submit" disabled={!newRoom.trim()}><Icon name="meeting_room" size={18} />Switch</button>
      </form>
    </section>

    {#if hasManagedRooms(room)}
      <section class="form">
        <h4>Managed room</h4>
        {#if inManagedRoom(room)}
          <p class="small">
            Full name: <span class="mono">{room.room}</span>
            <button class="link" onclick={() => copy(room!.room)}><Icon name={copied === room.room ? "check" : "content_copy"} size={16} />{copied === room.room ? "Copied" : "Copy"}</button>
          </p>
          {#if operator}
            <p class="small">You're an operator.</p>
            {#if savedPassword}
              <p class="small">
                Operator password: <span class="mono">{savedPassword}</span>
                <button class="link" onclick={() => copy(savedPassword!)}><Icon name={copied === savedPassword ? "check" : "content_copy"} size={16} />{copied === savedPassword ? "Copied" : "Copy"}</button>
              </p>
            {/if}
          {:else}
            <form
              onsubmit={(e) => {
                e.preventDefault();
                session?.identifyAsOperator(operatorPassword);
                operatorPassword = "";
              }}
            >
              <input bind:value={operatorPassword} placeholder="AB-123-456" aria-label="Operator password" class="mono" />
              <button class="button quiet" type="submit" disabled={!operatorPassword.trim()}><Icon name="key" size={18} />Become operator</button>
            </form>
            {#if followsOperators(room)}<p class="dim small">Only operators play, pause, seek or change the playlist.</p>{/if}
          {/if}
        {:else}
          <form
            onsubmit={(e) => {
              e.preventDefault();
              if (session?.createManagedRoom(managedName)) managedName = "";
            }}
          >
            <input bind:value={managedName} maxlength={ManagedRooms.MAX_BASE_NAME_LENGTH} placeholder="Name" aria-label="Managed room name" />
            <button class="button quiet" type="submit" disabled={!ManagedRooms.isValidBaseName(managedName)}><Icon name="lock" size={18} />Create managed room</button>
          </form>
        {/if}
      </section>
    {/if}

    <section class="row">
      <button class="button quiet" onclick={() => go("#/join")}><Icon name="edit" size={18} />Edit connection</button>
      <button class="button quiet" onclick={() => session?.disconnect()}><Icon name="logout" size={18} />Disconnect</button>
    </section>
  {/if}
</div>

<style>
  .people {
    display: grid;
    gap: 14px;
    padding: 0 16px 16px;
    overflow: auto;
    align-content: start;
  }
  h3,
  h4 {
    margin: 0 0 4px;
  }
  h4 {
    font-size: 15px;
  }
  p {
    margin: 0;
  }
  .small {
    font-size: 13px;
  }
  .notice {
    margin-top: 8px;
    padding: 10px;
    border-radius: 10px;
    background: var(--surface-high);
  }
  .switch {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .users {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 8px;
  }
  .users li {
    display: flex;
    gap: 10px;
    align-items: flex-start;
  }
  .who {
    flex: 1;
    min-width: 0;
    display: grid;
  }
  .file {
    overflow-wrap: anywhere;
  }
  .warn {
    color: var(--not-ready);
  }
  .dot {
    width: 10px;
    height: 10px;
    margin-top: 6px;
    border-radius: 50%;
    background: var(--not-ready);
    flex: none;
  }
  .dot.ready {
    background: var(--ready);
  }
  .dot.unknown {
    background: var(--text-dim);
  }
  .form form {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }
  .form input {
    flex: 1;
    min-width: 8ch;
    background: var(--surface-high);
    border: 0;
    border-radius: 8px;
    padding: 8px 10px;
  }
  .row {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .link {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    padding: 0 4px;
    font-size: 13px;
  }
</style>
