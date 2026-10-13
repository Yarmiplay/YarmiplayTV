/** A value that tells its subscribers when it changes; it follows Svelte's store contract, so screens use `$name`. */
export class Store<T> {
  private readonly listeners = new Set<(value: T) => void>();

  constructor(private current: T) {}

  get value(): T {
    return this.current;
  }

  set(value: T) {
    if (Object.is(value, this.current)) return;
    this.current = value;
    for (const l of [...this.listeners]) l(value);
  }

  update(change: (value: T) => T) {
    this.set(change(this.current));
  }

  subscribe(listener: (value: T) => void): () => void {
    this.listeners.add(listener);
    listener(this.current);
    return () => this.listeners.delete(listener);
  }
}
