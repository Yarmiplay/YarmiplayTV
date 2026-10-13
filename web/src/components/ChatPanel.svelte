<script lang="ts">
  import { changeSettings, session as sessionStore, settings } from "../app/context";
  import type { FeedMessage } from "../lib/sync/room";

  /** The room's chat and notifications, newest at the bottom, with the message box under them (1.8.x layout). */
  let { input = $bindable() }: { input?: HTMLInputElement } = $props();
  const session = $derived($sessionStore);
  let feed: FeedMessage[] = $state([]);
  let blocked = $state(new Set<string>());
  let connected = $state(false);
  let text = $state("");
  let list: HTMLOListElement | undefined = $state();

  $effect(() => {
    if (!session) return;
    const stops = [
      session.feed.subscribe((f) => {
        feed = f;
        queueMicrotask(() => list?.scrollTo({ top: list.scrollHeight }));
      }),
      session.blocked.subscribe((b) => (blocked = b)),
      session.room.subscribe((r) => (connected = r.status === "CONNECTED")),
    ];
    return () => stops.forEach((s) => s());
  });

  function send(event: SubmitEvent) {
    event.preventDefault();
    if (!text.trim()) return;
    session?.sendChat(text);
    text = "";
  }

  const time = (at: number) => new Date(at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
</script>

<div class="chat">
  <div class="bar">
    <label class="dim small">
      <input
        type="checkbox"
        checked={$settings.showChat}
        onchange={(e) => {
          const show = (e.target as HTMLInputElement).checked;
          changeSettings((s) => ({ ...s, showChat: show }));
          session?.setShowChat(show);
        }}
      />
      Show chat
    </label>
    {#if blocked.size}
      <span class="dim small">
        Blocked: {[...blocked].join(", ")}
        {#each [...blocked] as name (name)}
          <button class="link" onclick={() => session?.unblock(name)}>Unblock {name}</button>
        {/each}
      </span>
    {/if}
  </div>
  <ol bind:this={list} aria-live="polite">
    {#each feed as m (m.at + m.text)}
      <li class:error={m.isError} class:notice={!m.from}>
        <span class="dim small">{time(m.at)}</span>
        {#if m.from}
          <button class="from" title="Block {m.from} until you leave the room" onclick={() => session?.block(m.from!)}>{m.from}</button>
        {/if}
        <span class="text">{m.text}</span>
      </li>
    {/each}
  </ol>
  <form onsubmit={send}>
    <input
      bind:this={input}
      bind:value={text}
      maxlength={session?.maxChatLength ?? 150}
      placeholder={connected ? "Message the room" : "Join a room to chat"}
      disabled={!connected || !$settings.showChat}
      aria-label="Chat message"
    />
    <button class="button small" type="submit" disabled={!connected || !text.trim()}>Send</button>
  </form>
</div>

<style>
  .chat {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;
  }
  .bar {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding: 0 12px 6px;
  }
  .small {
    font-size: 13px;
  }
  ol {
    list-style: none;
    margin: 0;
    padding: 0 12px;
    overflow: auto;
    flex: 1;
    display: grid;
    align-content: start;
    gap: 4px;
  }
  li {
    overflow-wrap: anywhere;
  }
  li.notice .text {
    color: var(--text-dim);
  }
  li.error .text {
    color: var(--error);
  }
  .from {
    background: none;
    border: 0;
    padding: 0 4px;
    color: var(--accent);
    font-weight: 600;
    cursor: pointer;
  }
  .link {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    font-size: 13px;
  }
  form {
    display: flex;
    gap: 6px;
    padding: 8px 12px 12px;
  }
  form input {
    flex: 1;
    min-width: 0;
    background: var(--surface-high);
    border: 0;
    border-radius: 999px;
    padding: 8px 14px;
  }
  .button.small {
    padding: 6px 14px;
  }
</style>
