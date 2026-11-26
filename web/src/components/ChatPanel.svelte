<script lang="ts" module>
  let chatDraft = "";
</script>

<script lang="ts">
  import { changeSettings, player as playerStore, session as sessionStore, settings } from "../app/context";
  import { chatParts, formatChatTimestamp } from "../lib/chat/links";
  import { followsOperators } from "../lib/syncplay/client";
  import type { FeedMessage } from "../lib/sync/room";
  import Icon from "./Icon.svelte";

  /** The room's chat and notifications, newest at the bottom, with the message box under them (1.8.x layout). */
  let { input = $bindable() }: { input?: HTMLInputElement } = $props();
  const session = $derived($sessionStore);
  const mediaPlayer = $derived($playerStore);
  let feed: FeedMessage[] = $state([]);
  let blocked = $state(new Set<string>());
  let connected = $state(false);
  let loaded = $state(false);
  let position = $state(0);
  let text = $state(chatDraft);
  let list: HTMLOListElement | undefined = $state();

  $effect(() => {
    chatDraft = text;
  });

  $effect(() => {
    if (!mediaPlayer) return;
    return mediaPlayer.subscribe((s) => {
      loaded = s.loaded;
      position = s.position;
    });
  });

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
    chatDraft = "";
  }

  function seekToTimestamp(seconds: number) {
    const label = formatChatTimestamp(seconds);
    if (!mediaPlayer || !mediaPlayer.isFileLoaded) {
      session?.postLocal(`Load a video to jump to ${label}`);
      return;
    }
    const room = session?.room.value;
    if (room && room.status === "CONNECTED" && followsOperators(room)) {
      session?.postLocal("Only operators can seek in this managed room");
      return;
    }
    const dur = mediaPlayer.duration;
    if (dur > 0 && seconds > dur) {
      session?.postLocal(`Timestamp ${label} is past the end of this video`);
      return;
    }
    mediaPlayer.seek(seconds);
  }

  function insertTimestamp() {
    const stamp = formatChatTimestamp(position);
    const sep = text.length > 0 && !/\s$/.test(text) ? " " : "";
    const max = session?.maxChatLength ?? 150;
    text = (text + sep + stamp + " ").slice(0, max);
    chatDraft = text;
    input?.focus();
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
        <span class="text">
          {#each chatParts(m.text) as part, i (i)}
            {#if part.href}
              <a href={part.href} target="_blank" rel="noopener noreferrer">{part.text}</a>
            {:else if part.timestampSeconds !== undefined && m.from}
              <button type="button" class="ts" title="Jump to {formatChatTimestamp(part.timestampSeconds)}" onclick={() => seekToTimestamp(part.timestampSeconds!)}>{part.text}</button>
            {:else}
              {part.text}
            {/if}
          {/each}
        </span>
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
    {#if connected && loaded && $settings.showChat}
      <button class="button quiet small ts-insert" type="button" title="Insert current playback time" onclick={insertTimestamp}>{formatChatTimestamp(position)}</button>
    {/if}
    <button class="button small" type="submit" disabled={!connected || !text.trim()}><Icon name="send" size={18} />Send</button>
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
  .text a,
  .text .ts {
    color: var(--accent);
    text-decoration: underline;
  }
  .text .ts {
    background: none;
    border: 0;
    padding: 0;
    font: inherit;
    cursor: pointer;
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
    align-items: center;
    gap: 8px;
    padding: 10px 12px 14px;
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
    flex-shrink: 0;
  }
  .ts-insert {
    padding: 6px 10px;
    font-variant-numeric: tabular-nums;
  }
</style>
