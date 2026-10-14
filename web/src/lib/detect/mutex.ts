/**
 * One at a time: what kotlinx's Mutex (and a one-permit Semaphore, and synchronized) do for the Kotlin, for async
 * code. Each [lock] runs after the ones before it have finished, whether they succeeded or failed.
 */
export class Mutex {
  private tail: Promise<unknown> = Promise.resolve();
  private waiting = 0;

  /** Whether something holds the lock or waits for it. */
  get isLocked(): boolean {
    return this.waiting > 0;
  }

  lock<T>(body: () => Promise<T> | T): Promise<T> {
    this.waiting++;
    const run = this.tail.then(body).finally(() => this.waiting--);
    this.tail = run.catch(() => {});
    return run;
  }
}
