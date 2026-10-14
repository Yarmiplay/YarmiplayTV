import type { FingerprintEngine, FingerprintSession } from "./engine";
import type { SharedRun } from "./fingerprintMatcher";
import type { WorkerRequest, WorkerResponse } from "./workerProtocol";

/** The page's end of the fingerprint worker: a Worker, or in tests one end of a MessageChannel. */
export interface WorkerPort {
  postMessage(message: WorkerRequest, transfer: Transferable[]): void;
  onmessage: ((event: MessageEvent<WorkerResponse>) => void) | null;
  onerror?: ((event: ErrorEvent) => void) | null;
  terminate?(): void;
  close?(): void;
}

interface Waiting {
  resolve(response: WorkerResponse): void;
  reject(error: Error): void;
}

/**
 * Makes fingerprints and compares them on the fingerprint worker (worker.ts), with promises: the page only hands over
 * the decoded sound, a piece at a time (each piece's buffer moves to the worker rather than being copied), and gets
 * back the fingerprint, or the runs two fingerprints share.
 */
export class FingerprintWorker implements FingerprintEngine {
  private nextId = 1;
  private readonly waiting = new Map<number, Waiting>();
  private closed = false;

  constructor(private readonly port: WorkerPort) {
    port.onmessage = (event) => this.answered(event.data);
    port.onerror = (event) => this.failAll(new Error(`Fingerprint worker failed: ${event.message}`));
  }

  /** Starts the worker (bundled by Vite from worker.ts). */
  static start(): FingerprintWorker {
    return new FingerprintWorker(new Worker(new URL("./worker.ts", import.meta.url), { type: "module" }));
  }

  begin(): FingerprintSession {
    const id = this.nextId++;
    this.send({ type: "begin", id });
    let open = true;
    return {
      consume: (pcm) => {
        if (!open || pcm.length === 0) return;
        // A piece that is part of a bigger buffer is copied: moving the buffer would take the rest along.
        const own = pcm.byteOffset === 0 && pcm.byteLength === pcm.buffer.byteLength && pcm.buffer instanceof ArrayBuffer ? pcm : pcm.slice();
        this.send({ type: "consume", id, pcm: own }, [own.buffer as ArrayBuffer]);
      },
      finish: async () => {
        if (!open) throw new Error("Fingerprint already finished");
        open = false;
        const response = await this.ask({ type: "finish", id });
        if (response.type !== "fingerprint") throw new Error(`Unexpected ${response.type}`);
        return response.points;
      },
      cancel: () => {
        if (!open) return;
        open = false;
        this.send({ type: "cancel", id });
      },
    };
  }

  /** The fingerprint of [samples] (mono, 11025 Hz), each piece handed to the worker as it comes. */
  async fingerprint(samples: AsyncIterable<Int16Array> | Iterable<Int16Array>): Promise<Int32Array> {
    const session = this.begin();
    try {
      for await (const pcm of samples) session.consume(pcm);
    } catch (e) {
      session.cancel();
      throw e;
    }
    return session.finish();
  }

  async sharedRuns(a: Int32Array, b: Int32Array, minPoints: number, maxPoints: number, limit = 3): Promise<SharedRun[]> {
    // Copies are sent: the caller keeps its fingerprints (they are also cached).
    const response = await this.ask({ type: "match", id: this.nextId++, a, b, minPoints, maxPoints, limit });
    if (response.type !== "runs") throw new Error(`Unexpected ${response.type}`);
    return response.runs;
  }

  /** Stops the worker; what was asked and not yet answered fails. */
  close(): void {
    this.closed = true;
    this.failAll(new Error("Fingerprint worker closed"));
    this.port.terminate?.();
    this.port.close?.();
  }

  private send(request: WorkerRequest, transfer: Transferable[] = []): void {
    if (this.closed) throw new Error("Fingerprint worker closed");
    this.port.postMessage(request, transfer);
  }

  private ask(request: WorkerRequest): Promise<WorkerResponse> {
    return new Promise((resolve, reject) => {
      this.waiting.set(request.id, { resolve, reject });
      try {
        this.send(request);
      } catch (e) {
        this.waiting.delete(request.id);
        reject(e instanceof Error ? e : new Error(String(e)));
      }
    });
  }

  private answered(response: WorkerResponse): void {
    const waiting = this.waiting.get(response.id);
    if (!waiting) return;
    this.waiting.delete(response.id);
    if (response.type === "error") waiting.reject(new Error(response.message));
    else waiting.resolve(response);
  }

  private failAll(error: Error): void {
    const all = [...this.waiting.values()];
    this.waiting.clear();
    for (const w of all) w.reject(error);
  }
}
