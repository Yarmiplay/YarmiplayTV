/**
 * Finding openings and endings by listening (YarmiplayTV 1.9.5's tracking/detect, for the browser). The app gives a
 * [SkipDetector] its [AudioDecoder] (the player's FFmpeg), a [FingerprintCache] over an [OpfsCacheStore], an
 * [EpisodeSiblings] (or its own [SiblingFinder]), the network rule, and a [FingerprintWorker] to do the heavy work.
 */
export type { AudioDecoder, DecodedSound, DecodeOptions, EpisodeRef } from "./audioDecoder";
export { MemoryCacheStore, OpfsCacheStore, type CacheFile, type CacheStore } from "./cacheStore";
export { inlineEngine, type FingerprintEngine, type FingerprintSession } from "./engine";
export {
  EpisodeSiblings,
  episodeRef,
  localUrl,
  nearestFirst,
  type EpisodeFields,
  type EpisodeInfo,
  type LocalVideo,
  type PlayingFile,
  type ServerCopy,
  type ServerEpisode,
  type SiblingFinder,
  type Siblings,
} from "./episodeSiblings";
export {
  FingerprintCache,
  MAX_BYTES,
  decodeFingerprint,
  encodeFingerprint,
  keyOf,
  type SkipKind,
  type Span,
  type StoredFingerprint,
} from "./fingerprintCache";
export { endSeconds, sharedRuns, startSeconds, runLength, type SharedRun } from "./fingerprintMatcher";
export { FingerprintWorker, type WorkerPort } from "./fingerprintWorker";
export { audioDecoderFromFrames, type MonoFrame, type MonoFrameReader } from "./frameDecoder";
export { Fingerprinter, HOP_SECONDS, SAMPLE_RATE, SILENCE, fingerprint } from "./fingerprinter";
export { Resampler, type SampleSink } from "./resampler";
export { AGREE_SECONDS, SkipDetector, listenWindow, type PlayingEpisode, type SkipDetectorOptions, type SkipSegment } from "./skipDetector";
export type { WorkerRequest, WorkerResponse } from "./workerProtocol";
