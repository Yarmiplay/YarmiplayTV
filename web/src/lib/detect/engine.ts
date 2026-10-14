import { Fingerprinter } from "./fingerprinter";
import { sharedRuns, type SharedRun } from "./fingerprintMatcher";

/** A fingerprint being made: fed the sound a piece at a time, then finished. */
export interface FingerprintSession {
  /** Adds [pcm] (mono, 11025 Hz); it may be handed to a worker, which leaves it empty. */
  consume(pcm: Int16Array): void;
  /** The fingerprint of everything consumed (Fingerprinter.finish). */
  finish(): Promise<Int32Array>;
  /** Forgets it: nothing more is wanted. */
  cancel(): void;
}

/**
 * Where fingerprints are made and compared: on a worker in the app ([FingerprintWorker]), so minutes of sound don't
 * hold up the page; right here in tests ([inlineEngine]).
 */
export interface FingerprintEngine {
  begin(): FingerprintSession;
  /** [sharedRuns] of [a] and [b]. */
  sharedRuns(a: Int32Array, b: Int32Array, minPoints: number, maxPoints: number, limit?: number): Promise<SharedRun[]>;
}

/** Fingerprints made and compared on the calling thread: for tests, and where there are no workers. */
export const inlineEngine: FingerprintEngine = {
  begin(): FingerprintSession {
    let fingerprinter: Fingerprinter | null = new Fingerprinter();
    return {
      consume: (pcm) => fingerprinter?.consume(pcm),
      finish: async () => {
        const points = fingerprinter?.finish() ?? new Int32Array(0);
        fingerprinter = null;
        return points;
      },
      cancel: () => {
        fingerprinter = null;
      },
    };
  },
  sharedRuns: async (a, b, minPoints, maxPoints, limit) => sharedRuns(a, b, minPoints, maxPoints, limit),
};
