<script lang="ts">
  import { changeSettings, go, session as sessionStore, settings } from "../app/context";
  import { parseJoinLink } from "../lib/data/joinLink";
  import * as SavedRooms from "../lib/data/savedRooms";
  import type { SavedRoom } from "../lib/data/savedRooms";
  import * as ManagedRooms from "../lib/syncplay/managedRooms";
  import Icon from "../components/Icon.svelte";

  /**
   * Joining a room (MobileConnect.kt for the web): the server takes the whole width with the port under it; saved and
   * recent rooms rejoin with one press. Only YarmiplayServerTV with HTTPS is reachable from a browser, so there's no
   * TLS switch and no public Syncplay servers here.
   */
  const session = $derived($sessionStore);
  const fromLink = parseJoinLink(location.hash);
  const c = $settings.connection;
  let host = $state(fromLink?.host ?? c.host);
  let port = $state(String(fromLink?.port ?? c.port ?? 8999));
  let username = $state(c.username);
  let room = $state(fromLink?.room ?? c.room);
  let password = $state(fromLink ? "" : c.password);
  let lanName = $state(fromLink?.lanName ?? null);
  let more = $state(false);
  let operatorPassword = $state("");
  let saveThis = $state(false);
  let saveLabel = $state("");
  let rulesAgreed = $state($settings.acceptedRoomRules);
  let renaming = $state<string | null>(null);
  let renameText = $state("");
  let error = $state<string | null>(null);
  let connecting = $state(false);

  const saved = $derived($settings.savedRooms.filter((r) => r.saved));
  const recent = $derived($settings.savedRooms.filter((r) => !r.saved));
  const portNumber = $derived(Number(port));
  const complete = $derived(host.trim() !== "" && username.trim() !== "" && room.trim() !== "" && portNumber >= 1 && portNumber <= 65535);

  $effect(() => {
    if (!session) return;
    return session.connectionError.subscribe((e) => {
      if (!connecting || !e) return;
      error = explain(e);
      connecting = false;
    });
  });

  $effect(() => {
    if (!session) return;
    return session.room.subscribe((r) => {
      if (connecting && r.status === "CONNECTED") {
        connecting = false;
        go("#/room");
      }
    });
  });

  /** Why a browser couldn't join, in the user's terms. */
  function explain(reason: string): string {
    if (!/reach|refused|1006|didn't answer/i.test(reason)) return reason;
    const name = host.trim();
    if (/^[\d.]+$|:/.test(name) || !name.includes(".")) {
      return `${reason}. A browser can't check a server's certificate on an IP address or a name on your network: use the server's DuckDNS name (like myhome.duckdns.org), as the host's "Watch in a browser" link does.`;
    }
    return (
      `${reason}. A browser can only join YarmiplayServerTV 1.8 or later with HTTPS set up (its DuckDNS certificate); check the server name and port. ` +
      `On the server's own network, many routers can't reach their own public address: the apps get around that and a browser can't, ` +
      `so the host adds a "LAN name for browsers" in YarmiplayServerTV's Network settings and shares the "Watch in a browser" link again.`
    );
  }

  function join(profile = { host, port: portNumber, username, room, password }, opts: { lan?: string | null; saveAs?: string } = {}) {
    if (!session) return;
    if (!rulesAgreed) {
      error = "Agree to the community rules to join.";
      return;
    }
    error = null;
    connecting = true;
    changeSettings((s) => ({ ...s, acceptedRoomRules: true, connection: { ...profile, host: profile.host.trim(), room: profile.room.trim() } }));
    session.join(profile, {
      operatorPassword: operatorPassword || undefined,
      lanName: opts.lan ?? lanName,
      saveAs: opts.saveAs ?? (saveThis ? saveLabel : undefined),
    });
    if (fromLink) history.replaceState(null, "", "#/join");
  }

  function rejoin(r: SavedRoom) {
    host = r.host;
    port = String(r.port);
    username = r.username;
    room = r.room;
    password = r.password;
    join({ host: r.host, port: r.port, username: r.username, room: r.room, password: r.password }, { lan: r.lanName ?? null });
  }

  const edit = (change: (rooms: SavedRoom[]) => SavedRoom[]) => changeSettings((s) => ({ ...s, savedRooms: change(s.savedRooms) }));
  const label = (r: SavedRoom) => r.label || ManagedRooms.display(r.room);
</script>

<main class="join">
  <h1>Join a room</h1>

  {#if saved.length || recent.length}
    {#each [{ title: "Saved rooms", rooms: saved }, { title: "Recent rooms", rooms: recent }] as group (group.title)}
      {#if group.rooms.length}
        <section class="card rooms">
          <h2>{group.title}</h2>
          <ul>
            {#each group.rooms as r (SavedRooms.keyOf(r))}
              <li>
                <button class="star" class:on={r.saved} aria-label={r.saved ? "Remove from saved rooms" : "Save this room"}
                  onclick={() => edit((rooms) => SavedRooms.setSaved(rooms, SavedRooms.keyOf(r), !r.saved, r.label))}><Icon name={r.saved ? "star" : "star_border"} size={22} /></button>
                <div class="what">
                  {#if renaming === SavedRooms.keyOf(r)}
                    <form
                      onsubmit={(e) => {
                        e.preventDefault();
                        edit((rooms) => SavedRooms.setSaved(rooms, SavedRooms.keyOf(r), true, renameText));
                        renaming = null;
                      }}
                    >
                      <input bind:value={renameText} aria-label="Room name" />
                      <button class="link" type="submit">Save</button>
                    </form>
                  {:else}
                    <b>{label(r)}</b>
                  {/if}
                  <span class="dim small address">{r.host}:{r.port} · {ManagedRooms.label(r.room)} · as {r.username}</span>
                </div>
                <button class="button small" onclick={() => rejoin(r)}><Icon name="login" size={18} />Rejoin</button>
                <details class="menu">
                  <summary aria-label="More" title="More"><Icon name="more_vert" size={22} /></summary>
                  <div>
                    <button
                      class="link"
                      onclick={() => {
                        renaming = SavedRooms.keyOf(r);
                        renameText = r.label;
                      }}><Icon name="edit" size={18} />Rename</button
                    >
                    {#if r.saved}
                      <button class="link" onclick={() => edit((rooms) => SavedRooms.setSaved(rooms, SavedRooms.keyOf(r), false))}><Icon name="star_border" size={18} />Remove from saved</button>
                    {/if}
                    <button class="link" onclick={() => edit((rooms) => SavedRooms.forget(rooms, SavedRooms.keyOf(r)))}><Icon name="delete" size={18} />Forget</button>
                  </div>
                </details>
              </li>
            {/each}
          </ul>
        </section>
      {/if}
    {/each}
  {/if}

  <form
    class="card form"
    onsubmit={(e) => {
      e.preventDefault();
      join();
    }}
  >
    {#if fromLink}
      <p class="small">From the host's link. Pick a name and join.</p>
    {/if}
    <label>
      Server
      <input bind:value={host} placeholder="myhome.duckdns.org" autocomplete="off" spellcheck="false" required />
    </label>
    <label class="port">
      Port
      <input bind:value={port} inputmode="numeric" pattern="\d*" required />
    </label>
    <label>
      Your name
      <input bind:value={username} placeholder="As the others will see it" autocomplete="nickname" required />
    </label>
    <label>
      Room
      <input bind:value={room} placeholder="Any name" required />
    </label>
    <button type="button" class="link" onclick={() => (more = !more)} aria-expanded={more}><Icon name={more ? "expand_less" : "expand_more"} size={20} />{more ? "Fewer options" : "More options"}</button>
    {#if more}
      <label>
        Server password
        <input bind:value={password} type="password" autocomplete="off" />
      </label>
      {#if ManagedRooms.isManaged(room.trim())}
        <label>
          Operator password
          <input bind:value={operatorPassword} placeholder="AB-123-456" class="mono" />
        </label>
      {/if}
      {#if lanName}
        <p class="dim small">At home it also tries {lanName}.</p>
      {/if}
    {/if}
    <label class="check">
      <input type="checkbox" bind:checked={saveThis} />
      Save this room
    </label>
    {#if saveThis}
      <label>
        Name it (optional)
        <input bind:value={saveLabel} placeholder="Anime night" />
      </label>
    {/if}
    {#if !$settings.acceptedRoomRules}
      <label class="check">
        <input type="checkbox" bind:checked={rulesAgreed} />
        <span>I agree to the <a href="https://tv.yarmiplay.com/terms/" target="_blank" rel="noopener">community rules</a></span>
      </label>
    {/if}
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <button class="button" type="submit" disabled={!complete || connecting}><Icon name="login" size={20} />{connecting ? "Joining…" : "Join room"}</button>
    <p class="dim small">
      Browsers can join YarmiplayServerTV 1.8 or later with HTTPS set up. Plain Syncplay servers need the YarmiplayTV app.
    </p>
  </form>
</main>

<style>
  .join {
    max-width: 640px;
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
    margin: 0 0 8px;
    font-size: 17px;
  }
  .small {
    font-size: 13px;
  }
  .rooms ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 8px;
  }
  .rooms li {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .what {
    flex: 1;
    min-width: 0;
    display: grid;
  }
  .address {
    overflow-wrap: anywhere;
  }
  .star {
    display: inline-flex;
    background: none;
    border: 0;
    padding: 4px;
    font-size: 20px;
    cursor: pointer;
    color: var(--text-dim);
  }
  .star.on {
    color: var(--accent);
  }
  .menu {
    position: relative;
  }
  .menu summary {
    list-style: none;
    cursor: pointer;
    padding: 4px;
    display: flex;
    border-radius: 50%;
  }
  .menu summary::-webkit-details-marker {
    display: none;
  }
  .menu div {
    position: absolute;
    right: 0;
    z-index: 2;
    display: grid;
    background: var(--surface-high);
    border-radius: 10px;
    padding: 6px;
    min-width: 160px;
  }
  .form {
    display: grid;
    gap: 12px;
  }
  label {
    display: grid;
    gap: 4px;
    font-size: 14px;
  }
  label.check {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .port input {
    max-width: 12ch;
  }
  input:not([type="checkbox"]) {
    background: var(--surface-high);
    border: 0;
    border-radius: 10px;
    padding: 10px 12px;
    width: 100%;
  }
  .link {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    padding: 4px;
    text-align: left;
    justify-self: start;
  }
  .button.small {
    padding: 6px 14px;
  }
  .error {
    color: var(--error);
    margin: 0;
  }
</style>
