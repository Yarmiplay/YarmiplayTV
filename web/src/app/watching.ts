import buildInfo from "virtual:build-info";
import { languages } from "../lib/data/settings";
import {
  audioDecoderFromFrames,
  EpisodeSiblings,
  FingerprintCache,
  FingerprintWorker,
  MemoryCacheStore,
  OpfsCacheStore,
  SkipDetector,
  type PlayingEpisode,
  type PlayingFile,
  type ServerCopy as DetectServerCopy,
} from "../lib/detect";
import { MalClient } from "../lib/mal/client";
import type { EpisodeInfo as MalEpisode } from "../lib/mal/episode";
import { MalService } from "../lib/mal/service";
import { prefixedStorage } from "../lib/mal/storage";
import { MalTracker } from "../lib/mal/tracker";
import { PlaybackReporter, type EpisodeInfo } from "../lib/media";
import { decodeMono, mediaDuration } from "../lib/player/audioExtract";
import { HttpSource } from "../lib/player/bytes";
import type { PlayerState } from "../lib/player/mediaPlayer";
import { AniSkipClient } from "../lib/skip/aniskip";
import { SkipService } from "../lib/skip/skipService";
import type { SkipSegment as Skip } from "../lib/skip/segment";
import { Store } from "../lib/store";
import { episodeFromFile, type NowPlaying } from "../lib/sync/room";
import { followsOperators, type RoomState } from "../lib/syncplay/client";
import { library, player, session } from "./context";
import { sourceByKey, sources } from "./media";
import { settings } from "./settings";

/**
 * What happens around the playing video (the apps' PlaybackReporter, MalTracker and SkipService wiring): how far
 * playback got goes to the media servers, a watched episode to MyAnimeList, and openings and endings are skipped.
 */

// --- Episodes as each part needs them -------------------------------------------------------------

const malEpisode = (e: EpisodeInfo | null): MalEpisode | null =>
  e ? { title: e.seriesName, season: e.season, episode: e.episode, year: e.year, seriesKey: e.seriesKey, malId: e.malId } : null;

// --- MyAnimeList ---------------------------------------------------------------------------------------

/**
 * Where MyAnimeList's requests go: MyAnimeList refuses browsers, so through YarmiplayServerTV 1.8's passthrough, on
 * the room's server, or else the server of the last room joined.
 */
function passthroughBase(): string | null {
  const s = session.value;
  const room = s?.room.value;
  if (room?.status === "CONNECTED" && room.yarmiplay.serverKind === "YARMIPLAY") {
    if (!room.yarmiplay.capabilities.mal) return null;
    const base = s?.syncClient?.session?.baseUrl;
    if (base) return base;
  }
  const last = settings.value.savedRooms[0];
  if (!last) return null;
  const local = /^(localhost|127\.)/.test(last.host) && location.protocol === "http:";
  return `${local ? "http" : "https"}://${last.host}:${last.port}`;
}

export const malClient = new MalClient(buildInfo.malClientId, { passthroughBase });
export const mal = new MalService({
  client: malClient,
  storage: prefixedStorage("ytv:"),
  session: prefixedStorage("ytv:", () => sessionStorage),
});
const tracker = new MalTracker(mal);

/** Sign-in finishes on mal-callback.html, which hands the code back to this page. */
addEventListener("message", (event: MessageEvent) => {
  if (event.origin !== location.origin || event.data?.type !== "mal-callback") return;
  if (event.data.error || !event.data.code) return;
  mal.completeSignIn(event.data.code, event.data.state).catch((e: Error) => session.value?.postLocal(`MyAnimeList sign-in failed: ${e.message}`, true));
});

/** Opens MyAnimeList's sign-in in a small window (not noopener: its callback page talks back to this one). */
export function signInToMal() {
  window.open(mal.beginSignIn(), "mal-sign-in", "popup,width=520,height=720");
}

// --- Listening for openings and endings ----------------------------------------------------------------------

const unmetered = (): boolean => {
  const c = (navigator as Navigator & { connection?: { type?: string; saveData?: boolean } }).connection;
  if (c?.saveData) return false;
  if (c?.type) return c.type !== "cellular";
  // Only Chromium on Android says; a desktop browser is taken to be on Wi-Fi or Ethernet, a phone on mobile data.
  return !/Android|iPhone|iPad/.test(navigator.userAgent);
};

let detector: SkipDetector<PlayingEpisode & PlayingFile> | null = null;
function getDetector() {
  detector ??= new SkipDetector<PlayingEpisode & PlayingFile>({
    decoder: audioDecoderFromFrames((open, from, duration, opts) => decodeMono(open, from, duration, { languages: [...(opts.languages ?? [])], signal: opts.signal }), mediaDuration),
    cache: new FingerprintCache(OpfsCacheStore.supported ? new OpfsCacheStore() : new MemoryCacheStore()),
    siblings: new EpisodeSiblings(() => library.files, (name, directory) => episodeFromFile(name, directory || null)),
    mayListenRemote: () => unmetered() || settings.value.playback.detectOnAnyNetwork,
    languages: () => languages(settings.value.playback.audioLanguages),
    engine: FingerprintWorker.start(),
  });
  return detector;
}

/** The playing file as the detector reads it, with its season on the media servers. */
function playingEpisode(np: NowPlaying): PlayingEpisode & PlayingFile {
  const copies: DetectServerCopy[] = np.copies.flatMap((c) => {
    const source = sourceByKey(c.sourceKey);
    if (!source) return [];
    return [
      {
        itemId: c.itemId,
        serverName: source.displayName,
        seasonEpisodes: async () =>
          (await source.seasonEpisodes(c.itemId)).map((p) => ({
            itemId: p.itemId,
            url: p.url,
            fileName: p.fileName,
            sizeBytes: p.sizeBytes,
            durationSeconds: p.durationSeconds,
            episode: p.episode,
            open: () => HttpSource.open(p.url, { name: p.fileName, size: p.sizeBytes }),
          })),
      },
    ];
  });
  return { fileName: np.fileName, sizeBytes: np.size, url: np.key, remote: np.remote, open: np.open, episode: np.episode, local: np.local, copies };
}

// --- Skipping --------------------------------------------------------------------------------------------

let current: NowPlaying | null = null;
let room: RoomState | null = null;

export const skip = new SkipService({
  seek: (position) => player.value?.seek(position),
  canSeek: () => !room || !followsOperators(room),
  roomSkip: () => {
    const r = session.value?.roomAutoSkip();
    return { inRoom: r?.inRoom ?? false, on: r?.on ?? false };
  },
  settings: () => settings.value.playback,
  notify: (text) => session.value?.postLocal(text),
  identify: (info) => mal.identify(info),
  aniSkip: new AniSkipClient(),
  markers: async () => {
    const item = current?.item;
    const source = item ? sourceByKey(item.sourceKey) : undefined;
    return item && source ? ((await source.skipSegments(item.itemId)) as unknown as Skip[]).map(lowerKind) : [];
  },
  detect: async (kinds, { duration, signal }) => {
    if (!current || !settings.value.playback.detectSkips) return [];
    const found = await getDetector().detect(playingEpisode(current), duration, kinds.map((k) => k.toUpperCase() as "OPENING" | "ENDING"), signal);
    return found.map(lowerKind);
  },
});

/** The media module's and the detector's skip parts are "OPENING"/"ENDING"; the skip service's are lower case. */
function lowerKind(s: { kind: string; start: number; end: number }): Skip {
  return { kind: s.kind.toLowerCase() as Skip["kind"], start: s.start, end: s.end };
}

// --- Progress reports ------------------------------------------------------------------------------------

const reporter = new PlaybackReporter(() => sources.value);
reporter.startHeartbeat();

/** The skip service's settings changed (or the room's): it may skip or prompt differently now. */
export const skipSettingsChanged = () => skip.settingsChanged();

// --- Wiring ----------------------------------------------------------------------------------------------

let state: PlayerState | null = null;
let chaptersSeen: unknown = null;

function onPlayer(s: PlayerState) {
  state = s;
  const np = current;
  const loaded = s.loaded && np !== null;
  reporter.enabled = settings.value.reportPlayback;
  reporter.update(np ? { url: np.key, copies: np.copies, durationHint: np.durationHint } : null, {
    url: loaded ? np!.key : null,
    fileLoaded: s.loaded,
    paused: s.paused,
    seeking: s.buffering,
    position: s.position,
    duration: s.duration,
    eofReached: s.ended,
  });
  tracker.update(np ? { key: np.key, episode: malEpisode(np.episode), durationHint: np.durationHint } : null, {
    key: loaded ? np!.key : null,
    fileLoaded: s.loaded,
    duration: s.duration,
    position: s.position,
    ended: s.ended,
  });
  if (loaded && s.chapters !== chaptersSeen) {
    chaptersSeen = s.chapters;
    skip.loaded(s.duration, s.chapters);
  }
  if (loaded) skip.tick(s.position, s.paused, s.buffering);
}

function onNowPlaying(np: NowPlaying | null) {
  current = np;
  chaptersSeen = null;
  mal.playing(malEpisode(np?.episode ?? null));
  skip.fileChanged(np ? { key: np.key, episode: malEpisode(np.episode) } : null);
  if (state) onPlayer(state);
}

let stops: (() => void)[] = [];
session.subscribe((s) => {
  stops.forEach((stop) => stop());
  stops = [];
  if (!s) return;
  stops.push(
    s.nowPlaying.subscribe(onNowPlaying),
    s.room.subscribe((r) => {
      const before = room?.yarmiplay.roomSettings;
      room = r;
      if (r.yarmiplay.roomSettings !== before) skip.settingsChanged();
    }),
  );
});
player.subscribe((p) => p?.subscribe(onPlayer));
settings.subscribe(() => skip.settingsChanged());
// The skip button and the tracker check the time between the player's updates too.
setInterval(() => state?.loaded && current && !state.paused && skip.tick(player.value?.position ?? state.position, false), 250);

export type { MalEpisode };

// For the end-to-end tests and the browser's console.
Object.assign((globalThis as unknown as { ytv: object }).ytv ?? {}, { skip, mal });
