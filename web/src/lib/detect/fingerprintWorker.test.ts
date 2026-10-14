import { afterEach, describe, expect, it } from "vitest";
import { inlineEngine } from "./engine";
import { sharedRuns } from "./fingerprintMatcher";
import { FingerprintWorker, type WorkerPort } from "./fingerprintWorker";
import { HOP_SECONDS } from "./fingerprinter";
import { Synth } from "./synth.testing";
import { FingerprintWorkerHost, type WorkerRequest, type WorkerResponse } from "./workerProtocol";

// The worker's protocol end to end, over a MessageChannel (the buffers really move), with the worker's side in-process.

const open: MessageChannel[] = [];

function connected(): FingerprintWorker {
  const channel = new MessageChannel();
  open.push(channel);
  const host = new FingerprintWorkerHost((response, transfer) => channel.port2.postMessage(response, transfer));
  channel.port2.onmessage = (event: MessageEvent<WorkerRequest>) => host.handle(event.data);
  return new FingerprintWorker(channel.port1 as unknown as WorkerPort);
}

afterEach(() => {
  for (const c of open.splice(0)) {
    c.port1.close();
    c.port2.close();
  }
});

describe("FingerprintWorker", () => {
  it("makes the same fingerprint as the page would, the pieces moving to it", async () => {
    const pcm = Synth.music(1, 20.0);
    const worker = connected();
    const pieces = Array.from({ length: Math.ceil(pcm.length / 4000) }, (_, i) => pcm.slice(i * 4000, (i + 1) * 4000));
    const first = pieces[0];
    const points = await worker.fingerprint(pieces);
    expect(Array.from(points)).toEqual(Array.from(Synth.fingerprint(pcm)));
    // Moved, not copied.
    expect(first.length).toBe(0);
  });

  it("copies a piece that is part of a bigger buffer", async () => {
    const pcm = Synth.music(2, 10.0);
    const worker = connected();
    const session = worker.begin();
    for (let i = 0; i < pcm.length; i += 3000) session.consume(pcm.subarray(i, i + 3000));
    expect(Array.from(await session.finish())).toEqual(Array.from(Synth.fingerprint(pcm)));
    expect(pcm.length).toBe(Synth.music(2, 10.0).length);
  });

  it("keeps fingerprints apart and finds what two share", async () => {
    const theme = Synth.music(100, 40.0);
    const a = Synth.concat(Synth.music(1, 20.0), theme, Synth.music(2, 10.0));
    const b = Synth.concat(Synth.music(3, 5.0), theme, Synth.music(4, 15.0));
    const worker = connected();
    const [fa, fb] = await Promise.all([worker.fingerprint([a.slice()]), worker.fingerprint([b.slice()])]);
    const min = Math.round(15 / HOP_SECONDS);
    const max = Math.round(120 / HOP_SECONDS);
    const runs = await worker.sharedRuns(fa, fb, min, max);
    expect(runs).toEqual(sharedRuns(Synth.fingerprint(a), Synth.fingerprint(b), min, max));
    expect(runs.length).toBe(1);
    // The caller keeps its fingerprints.
    expect(fa.length).toBeGreaterThan(0);
    expect(await inlineEngine.sharedRuns(fa, fb, min, max)).toEqual(runs);
  });

  it("a cancelled fingerprint is forgotten, and finishing one twice fails", async () => {
    const worker = connected();
    const cancelled = worker.begin();
    cancelled.consume(Synth.music(1, 5.0));
    cancelled.cancel();
    const session = worker.begin();
    session.consume(Synth.music(1, 5.0));
    expect((await session.finish()).length).toBeGreaterThan(0);
    await expect(session.finish()).rejects.toThrow();
  });

  it("closing fails what is still waiting", async () => {
    const posted: WorkerRequest[] = [];
    const silent: WorkerPort = { postMessage: (m) => posted.push(m), onmessage: null };
    const worker = new FingerprintWorker(silent);
    const waiting = worker.sharedRuns(new Int32Array(1), new Int32Array(1), 1, 2);
    worker.close();
    await expect(waiting).rejects.toThrow("closed");
    expect(posted.map((m) => m.type)).toEqual(["match"]);
  });

  it("an error in the worker fails just that request", async () => {
    const answers: WorkerResponse[] = [];
    const host = new FingerprintWorkerHost((r) => answers.push(r));
    host.handle({ type: "finish", id: 7 });
    expect(answers).toEqual([{ type: "error", id: 7, message: "No fingerprint 7" }]);
  });
});
