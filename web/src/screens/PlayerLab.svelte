<script lang="ts">
  import { onMount } from "svelte";
  import { FileSource, HttpSource, type ByteSource } from "../lib/player/bytes";
  import { MediaPlayer, type PlayerState } from "../lib/player/mediaPlayer";
  import { formatTime } from "../lib/syncplay/models";

  /**
   * The player on its own, for trying files and links before rooms exist: what it decoded and how, the tracks, seeking
   * and the sync engine's 0.95× speed. The end-to-end tests drive it through window.playerLab.
   */
  let videoCanvas: HTMLCanvasElement;
  let subtitleCanvas: HTMLCanvasElement;
  let player: MediaPlayer | null = $state(null);
  let s: PlayerState | null = $state(null);
  let url = $state("");
  let loading = $state(false);
  let error = $state<string | null>(null);
  let dragging = $state<number | null>(null);

  onMount(() => {
    const p = new MediaPlayer(videoCanvas, subtitleCanvas);
    player = p;
    const stop = p.subscribe((state) => (s = state));
    (window as unknown as { playerLab: unknown }).playerLab = { player: p, open };
    return () => {
      stop();
      void p.close();
    };
  });

  async function open(source: ByteSource | Promise<ByteSource>) {
    if (!player) return;
    loading = true;
    error = null;
    try {
      await player.load(await source, { audioLanguages: ["jpn", "ja"], subtitleLanguages: ["eng", "en"] });
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
    }
  }

  function pickFile(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) void open(new FileSource(file));
  }

  function openUrl(event: SubmitEvent) {
    event.preventDefault();
    if (url.trim()) void open(HttpSource.open(url.trim()));
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    const file = event.dataTransfer?.files?.[0];
    if (file) void open(new FileSource(file));
  }

  async function save(withSubtitles: boolean) {
    const blob = await player?.screenshot(withSubtitles);
    if (!blob) return;
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${s?.name ?? "frame"}-${formatTime(player!.position).replace(/:/g, "-")}.png`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  }

  function onKey(event: KeyboardEvent) {
    if (!player || !s?.loaded || (event.target as HTMLElement).closest("input, select")) return;
    if (event.ctrlKey || event.altKey || event.metaKey) return;
    switch (event.key) {
      case " ":
      case "k":
        player.setPaused(!player.isPaused);
        break;
      case "ArrowLeft":
      case "j":
        player.seek(player.position - 10);
        break;
      case "ArrowRight":
      case "l":
        player.seek(player.position + 10);
        break;
      case "F5":
        void save(true);
        break;
      case "F6":
        void save(false);
        break;
      default:
        return;
    }
    event.preventDefault();
  }
</script>

<svelte:window onkeydown={onKey} />

<main class="lab">
  <header>
    <a href="#/">← YarmiplayTV</a>
    <h1>Player lab</h1>
  </header>

  <div class="stage" data-theme="dark" role="region" aria-label="Video" ondragover={(e) => e.preventDefault()} ondrop={onDrop}>
    <canvas bind:this={videoCanvas} class="video"></canvas>
    <canvas bind:this={subtitleCanvas} class="subs"></canvas>
    {#if loading || s?.buffering}
      <div class="chip">Loading…</div>
    {/if}
    {#if !s?.loaded && !loading}
      <div class="empty">Drop a video here, pick one below, or open a link.</div>
    {/if}
  </div>

  {#if s?.loaded}
    <section class="controls">
      <button class="button" onclick={() => player!.setPaused(!s!.paused)}>{s.paused ? "Play" : "Pause"}</button>
      <span class="mono time">{formatTime(dragging ?? s.position)} / {formatTime(s.duration)}</span>
      <input
        class="seek"
        type="range"
        min="0"
        max={s.duration || 1}
        step="0.1"
        value={dragging ?? s.position}
        oninput={(e) => (dragging = Number((e.target as HTMLInputElement).value))}
        onchange={(e) => {
          player!.seek(Number((e.target as HTMLInputElement).value));
          dragging = null;
        }}
        aria-label="Position"
      />
      <label>
        Speed
        <select value={String(s.speed)} onchange={(e) => player!.setSpeed(Number((e.target as HTMLSelectElement).value))}>
          <option value="1">1×</option>
          <option value="0.95">0.95× (catching up)</option>
        </select>
      </label>
      <label>
        Audio
        <select
          value={String(s.audioTrack ?? "")}
          onchange={(e) => player!.selectAudio(Number((e.target as HTMLSelectElement).value))}
        >
          {#each s.audioTracks as t (t.id)}
            <option value={String(t.id)}>{t.label}</option>
          {/each}
        </select>
      </label>
      <label>
        Subtitles
        <select
          value={String(s.subtitleTrack ?? "")}
          onchange={(e) => {
            const v = (e.target as HTMLSelectElement).value;
            void player!.selectSubtitle(v === "" ? null : Number(v));
          }}
        >
          <option value="">Off</option>
          {#each s.subtitleTracks as t (t.id)}
            <option value={String(t.id)} disabled={!t.playable}>{t.label}{t.playable ? "" : " (not yet)"}</option>
          {/each}
        </select>
      </label>
      <button class="button quiet" onclick={() => save(true)}>Screenshot (F5)</button>
      <button class="button quiet" onclick={() => save(false)}>Without subtitles (F6)</button>
    </section>
    <section class="card info">
      <div><span class="dim">File</span> {s.name}</div>
      {#if s.video}
        <div>
          <span class="dim">Video</span>
          {s.video.codec}, {s.video.width}×{s.video.height}, decoded in {s.video.decoder}
        </div>
      {/if}
      <div><span class="dim">Audio</span> {s.audioDecoder ? `decoded in ${s.audioDecoder}` : "none"}</div>
      {#if s.chapters.length}
        <div>
          <span class="dim">Chapters</span>
          {#each s.chapters as c, i (i)}
            <button class="chapter" onclick={() => player!.seek(c.start)}>{c.title || `Chapter ${i + 1}`} ({formatTime(c.start)})</button>
          {/each}
        </div>
      {/if}
    </section>
  {/if}

  {#if error || s?.error}
    <p class="error" role="alert">{error ?? s?.error}</p>
  {/if}

  <section class="card open">
    <label class="file">
      <span>Video on this device</span>
      <input type="file" accept="video/*,.mkv,.avi,.ts,.m2ts,.webm" onchange={pickFile} />
    </label>
    <form onsubmit={openUrl}>
      <input type="url" placeholder="https://… (read with Range requests)" bind:value={url} aria-label="Video link" />
      <button class="button quiet" type="submit">Open link</button>
    </form>
  </section>
</main>

<style>
  .lab {
    max-width: 1200px;
    margin: 0 auto;
    padding: 16px;
    display: grid;
    gap: 12px;
  }
  header {
    display: flex;
    gap: 16px;
    align-items: baseline;
  }
  h1 {
    margin: 0;
    font-size: 20px;
  }
  .stage {
    position: relative;
    aspect-ratio: 16 / 9;
    background: #000;
    border-radius: 12px;
    overflow: hidden;
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
  .chip {
    position: absolute;
    top: 12px;
    left: 12px;
    padding: 6px 12px;
    border-radius: 999px;
    background: rgb(0 0 0 / 0.6);
    color: #fff;
    font-size: 14px;
  }
  .empty {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    color: var(--text-dim);
  }
  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
  }
  .seek {
    flex: 1 1 240px;
    accent-color: var(--accent);
  }
  .time {
    min-width: 13ch;
  }
  select,
  input[type="url"] {
    background: var(--surface-high);
    border: 0;
    border-radius: 8px;
    padding: 6px 8px;
  }
  .info {
    display: grid;
    gap: 4px;
    font-size: 14px;
  }
  .chapter {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    padding: 0 6px;
  }
  .open {
    display: grid;
    gap: 12px;
  }
  .open form {
    display: flex;
    gap: 8px;
  }
  .open input[type="url"] {
    flex: 1;
  }
  .error {
    color: var(--error);
  }
</style>
