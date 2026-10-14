/// <reference lib="webworker" />
/**
 * The fingerprint worker: makes fingerprints and compares them off the page's thread, so listening to minutes of an
 * episode doesn't hold up playback or the screen. Started by [FingerprintWorker.start]; see workerProtocol.ts.
 */
import { FingerprintWorkerHost, type WorkerRequest } from "./workerProtocol";

const scope = self as unknown as DedicatedWorkerGlobalScope;
const host = new FingerprintWorkerHost((response, transfer) => scope.postMessage(response, transfer));
scope.onmessage = (event: MessageEvent<WorkerRequest>) => host.handle(event.data);
