<script lang="ts">
  import { onMount } from "svelte";
  import { attachPlayer, go, session as sessionStore, settings } from "../app/context";
  import { FileSource } from "../lib/player/bytes";
  import { MediaPlayer, type PlayerState } from "../lib/player/mediaPlayer";
  import { SeekFrames } from "../lib/player/seekFrames";
  import type { FeedMessage, PlaylistStatus } from "../lib/sync/room";
  import { followsOperators, type RoomState } from "../lib/syncplay/client";
  import * as ManagedRooms from "../lib/syncplay/managedRooms";
  import { formatTime } from "../lib/syncplay/models";
  import { mal, skip } from "../app/watching";
  import { isQuestion, noticeShowMillis, type MalNotice } from "../lib/mal/service";
  import type { SkipSegment } from "../lib/skip/segment";
  import ChatPanel from "./ChatPanel.svelte";
  import MalPanel from "./MalPanel.svelte";
  import PeoplePanel from "./PeoplePanel.svelte";
  import PlaylistPanel from "./PlaylistPanel.svelte";

  /** The player screen (MobilePlayer.kt for the web): always mounted, shown when [visible]. */
  let { visible }: { visible: boolean } = $props();

  let stage: HTMLDivElement;
  let videoCanvas: HTMLCanvasElement;
  let subtitleCanvas: HTMLCanvasElement;
  let pictureSubtitleCanvas: HTMLCanvasElement;
  let chatInput: HTMLInputElement | undefined = $state();
  let player = $state<MediaPlayer | null>(null);
  let s = $state<PlayerState | null>(null);
  let room = $state<RoomState | null>(null);
  let status = $state<PlaylistStatus>({ kind: "idle" });
  let toast = $state<FeedMessage | null>(null);
  let panel = $state<"playlist" | "room" | "chat" | "audio" | "subtitles" | "mal" | null>(null);
  let skipPrompt = $state<{ segment: SkipSegment; label: string } | null>(null);
  let segments = $state<SkipSegment[]>([]);
  let malNotice = $state<MalNotice | null>(null);
  let malTimer: ReturnType<typeof setTimeout> | undefined;
  $effect(() => skip.prompt.subscribe((p) => (skipPrompt = p)));
  $effect(() => skip.segments.subscribe((s) => (segments = s)));
  $effect(() =>
    mal.notice.subscribe((n) => {
      malNotice = n;
      clearTimeout(malTimer);
      if (n) malTimer = setTimeout(() => (malNotice = null), noticeShowMillis(n));
    }),
  );
  let controlsShown = $state(true);
  let hover = $state<{ x: number; time: number } | null>(null);
  let dragging = $state<number | null>(null);
  let seekNote = $state<string | null>(null);
  let preview: HTMLCanvasElement | undefined = $state();
  let previewShown = $state(false);
  let seekFrames: Promise<SeekFrames | null> | null = null;
  let seekFramesFor: unknown = null;
  let hideTimer: ReturnType<typeof setTimeout> | undefined;
  let toastTimer: ReturnType<typeof setTimeout> | undefined;
  let noteTimer: ReturnType<typeof setTimeout> | undefined;

  const session = $derived($sessionStore);
  const inRoom = $derived(room?.status === "CONNECTED");
  const readyCount = $derived(room?.users.filter((u) => u.isReady).length ?? 0);
  const seekStep = $derived($settings.playback.seekStepSeconds);

  onMount(() => {
    const p = new MediaPlayer(videoCanvas, subtitleCanvas, pictureSubtitleCanvas);
    player = p;
    attachPlayer(p);
    const stops = [p.subscribe((state) => (s = state))];
    (window as unknown as { playerLab: unknown }).playerLab = { player: p };
    return () => {
      stops.forEach((stop) => stop());
      void p.close();
    };
  });

  $effect(() => {
    if (!session) return;
    const stops = [
      session.room.subscribe((r) => (room = r)),
      session.status.subscribe((st) => (status = st)),
      session.toasts.subscribe((t) => {
        if (!t || (t.from && panel === "chat")) return;
        toast = t;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => (toast = null), t.isError ? 6000 : 3500);
      }),
    ];
    return () => stops.forEach((stop) => stop());
  });

  // The system's media controls (keyboard media keys, lock screen, the taskbar's controls): Media Session.
  $effect(() => {
    if (!("mediaSession" in navigator) || !s?.loaded) return;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: s.name,
      artist: room && inRoom ? `YarmiplayTV · ${ManagedRooms.display(room.room)}` : "YarmiplayTV",
      artwork: [{ src: "./favicon.svg", sizes: "any", type: "image/svg+xml" }],
    });
    const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
      ["play", () => player?.setPaused(false)],
      ["pause", () => player?.setPaused(true)],
      ["seekbackward", () => seekBy(-seekStep)],
      ["seekforward", () => seekBy(seekStep)],
      ["seekto", (d) => d.seekTime !== undefined && player?.seek(d.seekTime)],
      ["previoustrack", () => session?.skip(false)],
      ["nexttrack", () => session?.skip(true)],
    ];
    for (const [action, handler] of handlers) {
      try {
        navigator.mediaSession.setActionHandler(action, handler);
      } catch {
        // Not every browser has every action.
      }
    }
  });
  $effect(() => {
    if (!("mediaSession" in navigator) || !s?.loaded) return;
    navigator.mediaSession.playbackState = s.paused ? "paused" : "playing";
    try {
      navigator.mediaSession.setPositionState({ duration: s.duration, position: Math.min(s.position, s.duration), playbackRate: s.speed });
    } catch {
      // A duration of 0 (still opening) is refused.
    }
  });

  function poke() {
    controlsShown = true;
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      if (!s?.paused && !panel && dragging === null) controlsShown = false;
    }, 3000);
  }

  function togglePlay() {
    if (!player || !s?.loaded) return;
    player.setPaused(!player.isPaused);
    poke();
  }

  /** A quick seek, and a short note of where it landed (1.8.9). */
  function seekBy(delta: number) {
    if (!player || !s?.loaded) return;
    const to = Math.max(0, Math.min(player.position + delta, s.duration));
    player.seek(to);
    seekNote = `${delta > 0 ? "+" : "−"}${Math.abs(delta)} s · ${formatTime(to)}`;
    clearTimeout(noteTimer);
    noteTimer = setTimeout(() => (seekNote = null), 1200);
    poke();
  }

  /** The picture at [time] under the seek bar, for a file on this device (streams show the time only). */
  async function showPreview(time: number) {
    const source = player?.source;
    if (!(source instanceof FileSource)) {
      previewShown = false;
      return;
    }
    if (seekFramesFor !== source) {
      void seekFrames?.then((f) => f?.close());
      seekFramesFor = source;
      seekFrames = SeekFrames.open(source.file);
    }
    const frames = await seekFrames;
    const bitmap = await frames?.frame(time, 160);
    if (!bitmap || !preview) return;
    preview.width = bitmap.width;
    preview.height = bitmap.height;
    preview.getContext("2d")?.drawImage(bitmap, 0, 0);
    bitmap.close();
    previewShown = true;
  }

  /** Done with pictures for now: the second FFmpeg lets go of the file. */
  function hidePreview() {
    previewShown = false;
    void seekFrames?.then((f) => f?.close());
    seekFrames = null;
    seekFramesFor = null;
  }

  function trackTime(event: PointerEvent, track: HTMLElement): number {
    const box = track.getBoundingClientRect();
    const fraction = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    return fraction * (s?.duration ?? 0);
  }

  async function screenshot(withSubtitles: boolean) {
    const blob = await player?.screenshot(withSubtitles);
    if (!blob) return;
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${(s?.name ?? "frame").replace(/\.[^.]+$/, "")} ${formatTime(player!.position).replace(/:/g, "-")}.png`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    session?.postLocal(withSubtitles ? "Saved a screenshot" : "Saved a screenshot without the subtitles");
  }

  async function toggleFullscreen() {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await stage.requestFullscreen().catch(() => {});
  }

  function volume(delta: number) {
    if (!player || !s) return;
    player.setVolume(Math.min(1, Math.max(0, s.volume + delta)), false);
  }

  function pickFile(event: Event, manual: boolean) {
    const file = (event.target as HTMLInputElement).files?.[0];
    (event.target as HTMLInputElement).value = "";
    if (!file || !session) return;
    if (manual) void session.resolveManually(file);
    else void session.playLocal(file, inRoom);
  }

  /** The desktop app's keys (Shortcuts.kt). */
  function onKey(event: KeyboardEvent) {
    if (!visible || !player) return;
    const target = event.target as HTMLElement;
    // Typing goes to text fields; a file picker or a checkbox that keeps the focus mustn't swallow the keys.
    const typing = target.closest('input:not([type="file"]):not([type="checkbox"]):not([type="radio"]), textarea, select');
    if (typing && event.key !== "Escape") return;
    if (event.ctrlKey || event.altKey || event.metaKey) return;
    const key = event.key.length === 1 ? event.key.toLowerCase() : event.key;
    switch (key) {
      case " ":
      case "k":
        togglePlay();
        break;
      case "ArrowLeft":
      case "j":
        seekBy(-seekStep);
        break;
      case "ArrowRight":
      case "l":
        seekBy(seekStep);
        break;
      case "ArrowUp":
        volume(0.05);
        break;
      case "ArrowDown":
        volume(-0.05);
        break;
      case "m":
        player.setVolume(s?.volume ?? 1, !s?.muted);
        break;
      case "f":
        void toggleFullscreen();
        break;
      case "Escape":
        if (document.fullscreenElement) void document.exitFullscreen();
        else if (panel) panel = null;
        else go("#/room");
        break;
      case "Enter":
        panel = "chat";
        setTimeout(() => chatInput?.focus(), 0);
        break;
      case "n":
      case "MediaTrackNext":
        session?.skip(true);
        break;
      case "b":
      case "MediaTrackPrevious":
        session?.skip(false);
        break;
      case "MediaPlayPause":
        togglePlay();
        break;
      case "h":
        controlsShown = !controlsShown;
        break;
      case "F5":
        void screenshot(true);
        break;
      case "F6":
        void screenshot(false);
        break;
      case "p":
        void pictureInPicture();
        break;
      case "s":
        if (!skipPrompt) return;
        skip.skipNow();
        break;
      default:
        return;
    }
    event.preventDefault();
  }

  /** The browser's own picture-in-picture: the canvas mirrored into a video element. */
  let pipVideo: HTMLVideoElement | null = null;
  async function pictureInPicture() {
    if (document.pictureInPictureElement) {
      await document.exitPictureInPicture();
      return;
    }
    if (!("pictureInPictureEnabled" in document) || !document.pictureInPictureEnabled) {
      session?.postLocal("This browser has no picture-in-picture", true);
      return;
    }
    pipVideo ??= Object.assign(document.createElement("video"), { muted: true, playsInline: true });
    pipVideo.srcObject = videoCanvas.captureStream();
    await pipVideo.play();
    await pipVideo.requestPictureInPicture().catch(() => session?.postLocal("Picture-in-picture didn't open", true));
  }
</script>

<svelte:window onkeydown={onKey} />

<section class="player" class:hidden={!visible}>
  <!-- The keys work everywhere on this screen (onKey); a click on the picture plays or pauses, as in the apps. -->
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
  <div
    class="stage"
    bind:this={stage}
    role="region"
    aria-label="Video"
    onpointermove={poke}
    onclick={(e) => {
      if (e.target === e.currentTarget || (e.target as HTMLElement).tagName === "CANVAS") togglePlay();
    }}
    ondblclick={(e) => {
      const box = stage.getBoundingClientRect();
      const x = (e.clientX - box.left) / box.width;
      if (x < 0.3) seekBy(-seekStep);
      else if (x > 0.7) seekBy(seekStep);
      else void toggleFullscreen();
    }}
    ondragover={(e) => e.preventDefault()}
    ondrop={(e) => {
      e.preventDefault();
      const file = e.dataTransfer?.files?.[0];
      if (file && session) void session.playLocal(file, inRoom);
    }}
  >
    <canvas bind:this={videoCanvas} class="video"></canvas>
    <canvas bind:this={subtitleCanvas} class="subs"></canvas>
    <canvas bind:this={pictureSubtitleCanvas} class="subs"></canvas>

    {#if !s?.loaded && status.kind === "idle"}
      <div class="empty">
        <p>Nothing playing.</p>
        <label class="button">
          Open a video
          <input type="file" accept="video/*,.mkv,.avi,.ts,.m2ts,.webm" onchange={(e) => pickFile(e, false)} hidden />
        </label>
        <p class="dim small">Or drop one here. {inRoom ? "It becomes the room's selection." : "Join a room to watch together."}</p>
      </div>
    {/if}

    {#if status.kind === "loading" || status.kind === "resolving" || s?.buffering}
      <div class="chip top-left">Loading…</div>
    {/if}

    {#if status.kind === "notFound" || status.kind === "failed"}
      <div class="sheet" role="alert">
        <h2>{status.kind === "notFound" ? "Couldn't find this file" : "Couldn't play this file"}</h2>
        <p class="mono">{status.fileName}</p>
        <p class="dim">{status.reason}</p>
        <div class="row">
          <label class="button">
            Pick file
            <input type="file" accept="video/*,.mkv,.avi,.ts,.m2ts,.webm" onchange={(e) => pickFile(e, true)} hidden />
          </label>
          {#if status.kind === "notFound"}<button class="button quiet" onclick={() => go("#/search")}>Search servers</button>{/if}
          <button class="button quiet" onclick={() => session?.dismissStatus()}>Close</button>
        </div>
      </div>
    {/if}

    {#if status.kind === "untrusted"}
      <div class="sheet" role="alert">
        <h2>Open this link?</h2>
        <p class="mono">{status.url}</p>
        <p class="dim">The room picked a link that isn't on a trusted domain.</p>
        <div class="row">
          <button class="button" onclick={() => session?.playUntrusted(false)}>Open</button>
          {#if status.domain}
            <button class="button quiet" onclick={() => session?.playUntrusted(true)}>Always trust {status.domain}</button>
          {/if}
          <button class="button quiet" onclick={() => session?.dismissStatus()}>Not now</button>
        </div>
      </div>
    {/if}

    {#if skipPrompt}
      <button class="skip" onclick={() => skip.skipNow()}>
        <span class="skip-icon">⏭</span>{skipPrompt.label}<kbd>S</kbd>
      </button>
    {/if}

    {#if malNotice}
      <div class="mal-note" class:question={isQuestion(malNotice)}>
        <span>{malNotice.text}</span>
        {#if malNotice.undo}<button class="link-light" onclick={() => malNotice?.undo?.()}>Undo</button>{/if}
        {#if malNotice.confirm}<button class="link-light" onclick={() => malNotice?.confirm?.()}>Update</button>{/if}
        {#if malNotice.pick}<button class="link-light" onclick={() => (panel = "mal")}>Pick the entry</button>{/if}
      </div>
    {/if}

    {#if seekNote}
      <div class="note">{seekNote}</div>
    {/if}

    {#if toast}
      <div class="toast" class:error={toast.isError}>
        {#if toast.from}<b>{toast.from}:</b>{/if}
        {toast.text}
      </div>
    {/if}

    {#if s?.loaded}
      <div class="controls" class:shown={controlsShown || s.paused || panel !== null}>
        <div class="top">
          <button class="icon" onclick={() => go(inRoom ? "#/room" : "#/")} aria-label="Back">←</button>
          <span class="title">{s.name}</span>
          {#if room && inRoom}
            <button class="chip room-chip" onclick={() => (panel = panel === "room" ? null : "room")}>
              {ManagedRooms.label(room.room)} · {room.users.length} · {readyCount} ready
            </button>
          {/if}
          <button class="icon" onclick={pictureInPicture} aria-label="Picture-in-picture (P)" title="Picture-in-picture (P)">⧉</button>
          <button class="icon" onclick={toggleFullscreen} aria-label="Full screen (F)" title="Full screen (F)">⛶</button>
        </div>

        <div class="center">
          <button class="round" onclick={() => session?.skip(false)} disabled={!session || session.skipTarget(false) === null} aria-label="Previous entry (B)">⏮</button>
          <button class="round" onclick={() => seekBy(-seekStep)} aria-label="Back {seekStep} seconds">↺</button>
          <button class="round big" onclick={togglePlay} aria-label={s.paused ? "Play" : "Pause"}>{s.paused ? "▶" : "⏸"}</button>
          <button class="round" onclick={() => seekBy(seekStep)} aria-label="Forward {seekStep} seconds">↻</button>
          <button class="round" onclick={() => session?.skip(true)} disabled={!session || session.skipTarget(true) === null} aria-label="Next entry (N)">⏭</button>
        </div>

        <div class="bottom">
          <div
            class="track"
            role="slider"
            tabindex="-1"
            aria-label="Position"
            aria-valuemin="0"
            aria-valuemax={s.duration}
            aria-valuenow={s.position}
            onpointermove={(e) => {
              hover = { x: e.offsetX, time: trackTime(e, e.currentTarget) };
              void showPreview(dragging ?? hover.time);
            }}
            onpointerleave={() => {
              hover = null;
              if (dragging === null) hidePreview();
            }}
            onpointerdown={(e) => {
              e.currentTarget.setPointerCapture(e.pointerId);
              dragging = trackTime(e, e.currentTarget);
            }}
            onpointerup={(e) => {
              if (dragging !== null) player?.seek(trackTime(e, e.currentTarget));
              dragging = null;
              if (!hover) hidePreview();
            }}
          >
            <div class="fill" style="width: {((dragging ?? s.position) / (s.duration || 1)) * 100}%"></div>
            {#each segments as seg (seg.kind + seg.start)}
              <span class="part" style="left: {(seg.start / (s.duration || 1)) * 100}%; width: {((seg.end - seg.start) / (s.duration || 1)) * 100}%" title={seg.kind === "opening" ? "Opening" : "Ending"}></span>
            {/each}
            {#each s.chapters as c, i (i)}
              <span class="mark" style="left: {(c.start / (s.duration || 1)) * 100}%" title={c.title}></span>
            {/each}
            {#if hover || dragging !== null}
              <span class="hover-time" style="left: {hover?.x ?? 0}px">
                <canvas bind:this={preview} class="preview" class:shown={previewShown}></canvas>
                {formatTime(dragging ?? hover!.time)}
              </span>
            {/if}
          </div>
          <div class="row">
            <span class="mono time">{formatTime(dragging ?? s.position)} / {formatTime(s.duration)}</span>
            <input
              class="volume"
              type="range"
              min="0"
              max="1"
              step="0.05"
              value={s.muted ? 0 : s.volume}
              oninput={(e) => player?.setVolume(Number((e.target as HTMLInputElement).value), false)}
              aria-label="Volume"
            />
            {#if inRoom}
              <button class="chip" class:ready={room?.isReady} onclick={() => session?.toggleReady()}>
                <span class="dot"></span>{room?.isReady ? "Ready" : "Not ready"}
              </button>
            {/if}
            <span class="spacer"></span>
            <button class="icon" onclick={() => screenshot(true)} aria-label="Screenshot (F5)" title="Screenshot (F5; F6 without subtitles)">📷</button>
            <button class="icon" class:on={panel === "playlist"} onclick={() => (panel = panel === "playlist" ? null : "playlist")}>Playlist</button>
            <button class="icon" class:on={panel === "room"} onclick={() => (panel = panel === "room" ? null : "room")}>Room</button>
            <button class="icon" class:on={panel === "chat"} onclick={() => (panel = panel === "chat" ? null : "chat")}>Chat</button>
            <button class="icon" class:on={panel === "audio"} onclick={() => (panel = panel === "audio" ? null : "audio")}>Audio</button>
            <button class="icon" class:on={panel === "subtitles"} onclick={() => (panel = panel === "subtitles" ? null : "subtitles")}>Subtitles</button>
            <button class="icon" class:on={panel === "mal"} onclick={() => (panel = panel === "mal" ? null : "mal")}>MyAnimeList</button>
          </div>
        </div>
      </div>
    {/if}

    {#if panel}
      <aside class="side">
        <header>
          <h2>{{ playlist: "Playlist", room: "Room", chat: "Chat", audio: "Audio", subtitles: "Subtitles", mal: "MyAnimeList" }[panel]}</h2>
          <button class="icon" onclick={() => (panel = null)} aria-label="Close">✕</button>
        </header>
        {#if panel === "playlist"}
          <PlaylistPanel />
        {:else if panel === "room"}
          <PeoplePanel />
        {:else if panel === "chat"}
          <ChatPanel bind:input={chatInput} />
        {:else if panel === "mal"}
          <MalPanel />
        {:else if panel === "audio" && s}
          <ul class="tracks">
            {#each s.audioTracks as t (t.id)}
              <li><button class:on={s.audioTrack === t.id} onclick={() => player?.selectAudio(t.id)}>{t.label}</button></li>
            {/each}
          </ul>
        {:else if panel === "subtitles" && s}
          <ul class="tracks">
            <li><button class:on={s.subtitleTrack === null} onclick={() => player?.selectSubtitle(null)}>Off</button></li>
            {#each s.subtitleTracks as t (t.id)}
              <li>
                <button class:on={s.subtitleTrack === t.id} disabled={!t.playable} onclick={() => player?.selectSubtitle(t.id)}>
                  {t.label}{t.playable ? "" : " (not yet)"}
                </button>
              </li>
            {/each}
          </ul>
        {/if}
      </aside>
    {/if}
  </div>
  {#if room && inRoom && followsOperators(room)}
    <p class="dim small operators">Only the room's operators control playback here.</p>
  {/if}
</section>

<style>
  /* What's written straight on the video stays white on see-through black, in every theme. */
  .player {
    --on-video: #fff;
    height: 100%;
    display: flex;
    flex-direction: column;
    background: #000;
    color: var(--on-video);
  }
  .hidden {
    display: none;
  }
  .stage {
    position: relative;
    flex: 1;
    min-height: 240px;
    overflow: hidden;
    user-select: none;
  }
  .stage canvas {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
  }
  .subs {
    pointer-events: none;
  }
  .empty {
    color: var(--on-video);
  }
  .empty,
  .sheet {
    position: absolute;
    inset: 0;
    display: grid;
    place-content: center;
    justify-items: center;
    gap: 8px;
    text-align: center;
    padding: 16px;
  }
  /* The player takes the theme (2.0.3): its sheets and panels are the theme's cards over the video. */
  .sheet {
    background: rgb(0 0 0 / 0.55);
    z-index: 3;
  }
  .sheet > * {
    max-width: 560px;
  }
  .sheet h2 {
    background: var(--surface);
    color: var(--text);
    border-radius: 16px 16px 0 0;
    padding: 16px 16px 4px;
    margin: 0;
    width: 100%;
  }
  .sheet > p,
  .sheet > .row {
    background: var(--surface);
    color: var(--text);
    margin: 0;
    padding: 4px 16px;
    width: 100%;
  }
  .sheet > .row {
    border-radius: 0 0 16px 16px;
    padding-bottom: 16px;
  }
  .sheet h2 {
    margin: 0;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    justify-content: center;
  }
  .small {
    font-size: 14px;
  }
  .chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border-radius: 999px;
    background: rgb(0 0 0 / 0.55);
    color: #fff;
    border: 0;
    font-size: 14px;
    cursor: pointer;
  }
  .chip .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--not-ready);
  }
  .chip.ready .dot {
    background: var(--ready);
  }
  .top-left {
    position: absolute;
    top: 64px;
    left: 16px;
    z-index: 2;
  }
  .note {
    position: absolute;
    top: 45%;
    left: 50%;
    transform: translate(-50%, -50%);
    padding: 8px 16px;
    border-radius: 12px;
    background: rgb(0 0 0 / 0.6);
    font-variant-numeric: tabular-nums;
    z-index: 2;
  }
  .toast {
    position: absolute;
    left: 50%;
    bottom: 120px;
    transform: translateX(-50%);
    max-width: min(90%, 640px);
    padding: 8px 14px;
    border-radius: 12px;
    background: rgb(0 0 0 / 0.7);
    z-index: 4;
  }
  .toast.error {
    border: 1px solid var(--error);
  }
  .controls {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    opacity: 0;
    transition: opacity 0.2s;
    pointer-events: none;
    z-index: 1;
  }
  .controls.shown {
    opacity: 1;
  }
  .controls.shown > * {
    pointer-events: auto;
  }
  .top {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 16px;
    background: linear-gradient(rgb(0 0 0 / 0.8), transparent);
  }
  .title {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .center {
    display: flex;
    gap: 16px;
    justify-content: center;
    align-items: center;
    pointer-events: none !important;
  }
  .center > * {
    pointer-events: auto;
  }
  .round {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    border: 0;
    background: rgb(0 0 0 / 0.45);
    color: #fff;
    font-size: 22px;
    cursor: pointer;
  }
  .round.big {
    width: 72px;
    height: 72px;
    font-size: 28px;
  }
  .round:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .bottom {
    padding: 8px 16px 12px;
    background: linear-gradient(transparent, rgb(0 0 0 / 0.9));
    display: grid;
    gap: 6px;
  }
  .bottom .row {
    justify-content: flex-start;
  }
  .track {
    position: relative;
    height: 22px;
    cursor: pointer;
    touch-action: none;
  }
  .track::before,
  .fill {
    content: "";
    position: absolute;
    left: 0;
    top: 8px;
    height: 6px;
    border-radius: 3px;
  }
  .track::before {
    right: 0;
    background: rgb(255 255 255 / 0.2);
  }
  .fill {
    background: var(--accent);
  }
  /* The Skip button stays in one place just above the controls, whether they show or not (1.9.6). */
  .skip {
    position: absolute;
    right: 24px;
    bottom: 110px;
    z-index: 3;
    display: inline-flex;
    align-items: center;
    gap: 10px;
    padding: 10px 16px;
    border-radius: 12px;
    border: 1px solid rgb(255 255 255 / 0.15);
    background: rgb(20 20 24 / 0.72);
    backdrop-filter: blur(10px);
    color: #fff;
    font-weight: 600;
    cursor: pointer;
  }
  .skip-icon {
    color: var(--accent);
  }
  .skip kbd {
    font: 12px ui-monospace, monospace;
    padding: 1px 6px;
    border-radius: 5px;
    border: 1px solid rgb(255 255 255 / 0.35);
    border-bottom-width: 2px;
  }
  /* MyAnimeList's brief note: two seconds, one line, in the corner (1.8.9). */
  .mal-note {
    position: absolute;
    top: 64px;
    right: 16px;
    z-index: 4;
    display: flex;
    gap: 10px;
    align-items: center;
    padding: 6px 12px;
    border-radius: 10px;
    background: rgb(0 0 0 / 0.7);
    color: #fff;
    font-size: 14px;
  }
  .mal-note.question {
    border: 1px solid var(--accent);
  }
  .link-light {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    font-weight: 600;
  }
  .part {
    position: absolute;
    top: 8px;
    height: 6px;
    background: rgb(255 255 255 / 0.45);
    border-radius: 3px;
  }
  .mark {
    position: absolute;
    top: 6px;
    width: 2px;
    height: 10px;
    background: rgb(255 255 255 / 0.7);
  }
  .preview {
    display: none;
    width: 160px;
    border-radius: 6px;
    margin-bottom: 4px;
  }
  .preview.shown {
    display: block;
  }
  .hover-time {
    position: absolute;
    bottom: 24px;
    display: grid;
    justify-items: center;
    transform: translateX(-50%);
    padding: 2px 8px;
    border-radius: 6px;
    background: rgb(0 0 0 / 0.8);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
  }
  .time {
    font-size: 14px;
    min-width: 14ch;
  }
  .volume {
    width: 90px;
    accent-color: var(--accent);
  }
  .spacer {
    flex: 1;
  }
  .icon {
    background: none;
    border: 0;
    color: #fff;
    padding: 6px 8px;
    border-radius: 8px;
    cursor: pointer;
    font-size: 14px;
  }
  .icon.on,
  .icon:hover {
    background: rgb(255 255 255 / 0.12);
  }
  .side {
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    width: min(380px, 92%);
    background: var(--surface);
    color: var(--text);
    display: flex;
    flex-direction: column;
    z-index: 5;
    box-shadow: -8px 0 24px rgb(0 0 0 / 0.4);
  }
  .side header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 16px;
  }
  .side h2 {
    margin: 0;
    font-size: 17px;
  }
  .side .icon {
    color: var(--text);
  }
  .tracks {
    list-style: none;
    margin: 0;
    padding: 0 8px;
    overflow: auto;
  }
  .tracks button {
    width: 100%;
    text-align: left;
    background: none;
    border: 0;
    padding: 10px 12px;
    border-radius: 10px;
    cursor: pointer;
  }
  .tracks button.on {
    background: var(--surface-high);
    color: var(--accent);
  }
  .operators {
    margin: 4px 16px;
  }
</style>
