import type { ByteSource } from "../player/bytes";

/**
 * An episode file to compare the sound of (EpisodeFile.kt): episode [number] of its season. The app makes these for
 * the files of a season and says how to read each with [open].
 */
export interface EpisodeRef {
  /** Its episode number in its season. */
  readonly number: number;
  /** Who it is in the fingerprint cache: [keyOf] its file name, size and address. */
  readonly key: string;
  readonly fileName: string;
  readonly sizeBytes: number;
  /** Where it is: a server's address, or a made-up "file:" one for a file on this device. */
  readonly url: string;
  /** Seconds, when already known (a server says); otherwise the decoder is asked. */
  readonly duration: number | null;
  /** Streamed from a server, so reading it downloads it: only on an unmetered network, unless the user allows any. */
  readonly remote: boolean;
  /** Its bytes, for the [AudioDecoder]; closed once the sound needed has been read. */
  open(): Promise<ByteSource>;
}

/** What [AudioDecoder.decodeMono] is asked for besides where. */
export interface DecodeOptions {
  /** Samples a second wanted: always the Fingerprinter's SAMPLE_RATE (11025). */
  readonly sampleRate: number;
  /** The audio track in the first of these languages it has (ISO codes as the user typed them), else its first. */
  readonly languages: readonly string[];
  /** Reading stops (the samples end, or throw an AbortError) once this aborts. */
  readonly signal: AbortSignal;
}

/** The sound [AudioDecoder.decodeMono] hands over. */
export interface DecodedSound {
  /** The time in seconds the first sample is at: decoders start at the frame before the one asked for. */
  readonly start: number;
  /**
   * Mono 16-bit samples at [DecodeOptions.sampleRate], a piece at a time, in order and without gaps, until the time
   * asked for has been read (or the file ends). Each piece is the detector's to keep: it may be handed to a worker,
   * which leaves it empty.
   */
  readonly samples: AsyncIterable<Int16Array>;
}

/**
 * Reads a video's sound for fingerprinting: the app's own decoder (the player's FFmpeg, libav.js), as AudioDecoder.kt
 * is the platform's. Channels are mixed down and the sound resampled to mono 11025 Hz; FFmpeg's swresample can do
 * that, or the [Resampler] here can, from the decoded frames.
 */
export interface AudioDecoder {
  /** How long [source] is in seconds; null when it can't be read. */
  duration(source: ByteSource): Promise<number | null>;

  /**
   * Decodes [seconds] of [source]'s sound from [fromSeconds] on (as mono 16-bit [DecodeOptions.sampleRate] Hz
   * samples, from the audio track in the first of [DecodeOptions.languages] it has, else its first). Null when it
   * can't be decoded (no audio, an unknown codec); a throw counts the same.
   */
  decodeMono(source: ByteSource, fromSeconds: number, seconds: number, options: DecodeOptions): Promise<DecodedSound | null>;
}
