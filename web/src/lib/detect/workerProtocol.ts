import { Fingerprinter } from "./fingerprinter";
import { sharedRuns, type SharedRun } from "./fingerprintMatcher";

/**
 * What the page asks the fingerprint worker. Each fingerprint is a session [id] the page picks: "begin" it, "consume"
 * its sound (the samples' buffer is transferred), then "finish" it for a "fingerprint" answer, or "cancel" it. A
 * "match" is answered with "runs". Anything that goes wrong is answered with "error" for that id.
 */
export type WorkerRequest =
  | { readonly type: "begin"; readonly id: number }
  | { readonly type: "consume"; readonly id: number; readonly pcm: Int16Array }
  | { readonly type: "finish"; readonly id: number }
  | { readonly type: "cancel"; readonly id: number }
  | {
      readonly type: "match";
      readonly id: number;
      readonly a: Int32Array;
      readonly b: Int32Array;
      readonly minPoints: number;
      readonly maxPoints: number;
      readonly limit: number;
    };

/** What the fingerprint worker answers; "fingerprint" transfers the points' buffer. */
export type WorkerResponse =
  | { readonly type: "fingerprint"; readonly id: number; readonly points: Int32Array }
  | { readonly type: "runs"; readonly id: number; readonly runs: SharedRun[] }
  | { readonly type: "error"; readonly id: number; readonly message: string };

/** Sends an answer, transferring [transfer]. */
export type Post = (response: WorkerResponse, transfer: Transferable[]) => void;

/**
 * The worker's side of the protocol, apart from the worker itself so it can be tested anywhere: the fingerprints being
 * made, by session id.
 */
export class FingerprintWorkerHost {
  private readonly sessions = new Map<number, Fingerprinter>();

  constructor(private readonly post: Post) {}

  handle(request: WorkerRequest): void {
    try {
      switch (request.type) {
        case "begin":
          this.sessions.set(request.id, new Fingerprinter());
          break;
        case "consume":
          this.sessions.get(request.id)?.consume(request.pcm);
          break;
        case "finish": {
          const fingerprinter = this.sessions.get(request.id);
          this.sessions.delete(request.id);
          if (!fingerprinter) throw new Error(`No fingerprint ${request.id}`);
          const points = fingerprinter.finish();
          this.post({ type: "fingerprint", id: request.id, points }, [points.buffer]);
          break;
        }
        case "cancel":
          this.sessions.delete(request.id);
          break;
        case "match": {
          const runs = sharedRuns(request.a, request.b, request.minPoints, request.maxPoints, request.limit);
          this.post({ type: "runs", id: request.id, runs }, []);
          break;
        }
      }
    } catch (e) {
      this.sessions.delete(request.id);
      this.post({ type: "error", id: request.id, message: e instanceof Error ? e.message : String(e) }, []);
    }
  }
}
