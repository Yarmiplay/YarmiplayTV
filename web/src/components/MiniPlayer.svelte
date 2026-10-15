<script lang="ts">
  import { go, player as playerStore, session as sessionStore } from "../app/context";
  import type { PlayerState } from "../lib/player/mediaPlayer";
  import type { NowPlaying } from "../lib/sync/room";
  import type { RoomState } from "../lib/syncplay/client";
  import * as FileNames from "../lib/media/fileNames";
  import { formatTime } from "../lib/syncplay/models";
  import Icon from "./Icon.svelte";

  let ps = $state<PlayerState | null>(null);
  let nowPlaying = $state<NowPlaying | null>(null);
  let room = $state<RoomState | null>(null);

  const session = $derived($sessionStore);
  const player = $derived($playerStore);

  $effect(() => {
    if (!player) return;
    return player.subscribe((st) => (ps = st));
  });

  $effect(() => {
    if (!session) return;
    const stops = [
      session.nowPlaying.subscribe((np) => (nowPlaying = np)),
      session.room.subscribe((r) => (room = r)),
    ];
    return () => stops.forEach((s) => s());
  });

  const loaded = $derived(ps?.loaded ?? false);
  const paused = $derived(ps?.paused ?? true);
  const fileName = $derived(nowPlaying?.fileName ?? ps?.name ?? "");
  const isAudio = $derived(FileNames.isAudio(fileName));
  const title = $derived(nowPlaying?.title || ps?.name || "Nothing playing");
  const subtitle = $derived(nowPlaying?.episode?.seriesName ?? (isAudio ? "Music" : ""));
  const position = $derived(ps?.position ?? 0);
  const duration = $derived(ps?.duration ?? 0);
  const progressPercent = $derived(duration > 0 ? (position / duration) * 100 : 0);
  const inRoom = $derived(room?.status === "CONNECTED");
  const canSkipForward = $derived(session?.skipTarget(true) !== null);
  const canSkipBackward = $derived(session?.skipTarget(false) !== null);

  function togglePlay(event: MouseEvent) {
    event.stopPropagation();
    if (!player) return;
    player.setPaused(!paused);
  }

  function skipNext(event: MouseEvent) {
    event.stopPropagation();
    session?.skip(true);
  }

  function skipPrev(event: MouseEvent) {
    event.stopPropagation();
    session?.skip(false);
  }

  function openPlayer() {
    go("#/player");
  }
</script>

{#if loaded}
  <!-- svelte-ignore a11y_click_events_have_key_events -->
  <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
  <aside class="mini-player" onclick={openPlayer} role="region" aria-label="Now playing controls">
    <div class="progress-bar">
      <div class="progress-fill" style="width: {progressPercent}%;"></div>
    </div>
    <div class="mini-content">
      <div class="thumb" class:music={isAudio}>
        <Icon name={isAudio ? "audiotrack" : "movie"} size={22} />
      </div>
      <div class="track-info">
        <span class="track-title">{title}</span>
        <span class="track-sub">
          {#if subtitle}<span class="series">{subtitle} · </span>{/if}
          <span class="time">{formatTime(position)} / {formatTime(duration)}</span>
        </span>
      </div>
      <div class="mini-controls">
        {#if inRoom && canSkipBackward}
          <button class="icon-btn" onclick={skipPrev} aria-label="Previous track" title="Previous track">
            <Icon name="fast_rewind" size={20} />
          </button>
        {/if}
        <button class="icon-btn play-btn" onclick={togglePlay} aria-label={paused ? "Play" : "Pause"} title={paused ? "Play" : "Pause"}>
          <Icon name={paused ? "play_arrow" : "pause"} size={26} />
        </button>
        {#if inRoom && canSkipForward}
          <button class="icon-btn" onclick={skipNext} aria-label="Next track" title="Next track">
            <Icon name="fast_forward" size={20} />
          </button>
        {/if}
      </div>
    </div>
  </aside>
{/if}

<style>
  .mini-player {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    z-index: 100;
    background: var(--surface-high);
    border-top: 1px solid var(--surface-border, rgba(255, 255, 255, 0.08));
    box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.35);
    cursor: pointer;
    user-select: none;
    transition: background-color 0.15s ease;
  }
  .mini-player:hover {
    background: var(--surface-hover, var(--surface-high));
  }
  .progress-bar {
    width: 100%;
    height: 3px;
    background: rgba(255, 255, 255, 0.1);
  }
  .progress-fill {
    height: 100%;
    background: var(--accent);
    transition: width 0.25s linear;
  }
  .mini-content {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 16px;
    max-width: 1100px;
    margin: 0 auto;
  }
  .thumb {
    width: 40px;
    height: 40px;
    border-radius: 8px;
    background: var(--surface);
    display: grid;
    place-items: center;
    color: var(--text-dim);
    flex-shrink: 0;
  }
  .thumb.music {
    background: var(--accent-surface, rgba(61, 165, 244, 0.15));
    color: var(--accent);
  }
  .track-info {
    display: flex;
    flex-direction: column;
    min-width: 0;
    flex: 1;
    gap: 2px;
  }
  .track-title {
    font-size: 14px;
    font-weight: 600;
    color: var(--text);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .track-sub {
    font-size: 12px;
    color: var(--text-dim);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .mini-controls {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .icon-btn {
    background: none;
    border: none;
    color: var(--text);
    padding: 6px;
    border-radius: 50%;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background 0.15s;
  }
  .icon-btn:hover {
    background: var(--surface);
  }
  .play-btn {
    color: var(--accent);
  }
  @media (max-width: 720px) {
    .mini-player {
      bottom: 56px; /* Sits above the mobile bottom navigation bar */
    }
  }
</style>
