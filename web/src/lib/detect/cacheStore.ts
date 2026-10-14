/** A file the fingerprint cache keeps: where, how big, and when last used (milliseconds, only compared). */
export interface CacheFile {
  readonly path: string;
  readonly size: number;
  readonly lastUsed: number;
}

/**
 * Where the fingerprint cache keeps its files: paths like "fp/<key>-op.bin" and "obs/<key>.json", one folder deep. In
 * the browser the Origin Private File System ([OpfsCacheStore]); in tests memory ([MemoryCacheStore]).
 */
export interface CacheStore {
  /** The file's bytes; null when there's no such file. */
  read(path: string): Promise<Uint8Array | null>;
  /** Replaces the file with [data] whole: a reader never sees half of it. */
  write(path: string, data: Uint8Array): Promise<void>;
  /** Removes the file, if it is there. */
  remove(path: string): Promise<void>;
  /** Marks the file used now, for least-recently-used pruning. */
  touch(path: string): Promise<void>;
  /** Every file in folder [dir]. */
  list(dir: string): Promise<CacheFile[]>;
}

/** A [CacheStore] in memory: for tests, and where the browser has no Origin Private File System. */
export class MemoryCacheStore implements CacheStore {
  private readonly files = new Map<string, { data: Uint8Array; lastUsed: number }>();
  /** A clock that always moves on, so two uses in one millisecond still come in order. */
  private clock = 0;

  async read(path: string): Promise<Uint8Array | null> {
    return this.files.get(path)?.data.slice() ?? null;
  }

  async write(path: string, data: Uint8Array): Promise<void> {
    this.files.set(path, { data: data.slice(), lastUsed: ++this.clock });
  }

  async remove(path: string): Promise<void> {
    this.files.delete(path);
  }

  async touch(path: string): Promise<void> {
    const file = this.files.get(path);
    if (file) file.lastUsed = ++this.clock;
  }

  async list(dir: string): Promise<CacheFile[]> {
    const prefix = dir + "/";
    return [...this.files]
      .filter(([path]) => path.startsWith(prefix))
      .map(([path, f]) => ({ path, size: f.data.length, lastUsed: f.lastUsed }));
  }

  /** Every path kept, for tests. */
  paths(): string[] {
    return [...this.files.keys()];
  }
}

type Directory = FileSystemDirectoryHandle & { values(): AsyncIterable<FileSystemHandle> };

/**
 * A [CacheStore] in the Origin Private File System, under folder [root]: the browser keeps it for this site like the
 * apps keep their cache folder, and a write lands whole (createWritable swaps the file in when it is closed, as the
 * apps write a ".part" file and rename it). Files are "touched" by writing them again, which sets their time.
 */
export class OpfsCacheStore implements CacheStore {
  private rootDir: Promise<FileSystemDirectoryHandle> | null = null;

  constructor(private readonly root = "skip-detect") {}

  /** Whether this browser has an Origin Private File System. */
  static get supported(): boolean {
    return typeof navigator !== "undefined" && typeof navigator.storage?.getDirectory === "function";
  }

  async read(path: string): Promise<Uint8Array | null> {
    const file = await this.file(path, false);
    if (!file) return null;
    return new Uint8Array(await (await file.getFile()).arrayBuffer());
  }

  async write(path: string, data: Uint8Array): Promise<void> {
    const file = (await this.file(path, true))!;
    const writable = await file.createWritable();
    try {
      await writable.write(data as Uint8Array<ArrayBuffer>);
      await writable.close();
    } catch (e) {
      await writable.abort().catch(() => {});
      throw e;
    }
  }

  async remove(path: string): Promise<void> {
    const [dir, name] = split(path);
    try {
      await (await this.dir(dir, false))?.removeEntry(name);
    } catch (e) {
      if (!isNotFound(e)) throw e;
    }
  }

  async touch(path: string): Promise<void> {
    const data = await this.read(path);
    if (data) await this.write(path, data);
  }

  async list(dir: string): Promise<CacheFile[]> {
    const folder = (await this.dir(dir, false)) as Directory | null;
    if (!folder) return [];
    const out: CacheFile[] = [];
    for await (const entry of folder.values()) {
      if (entry.kind !== "file") continue;
      try {
        const file = await (entry as FileSystemFileHandle).getFile();
        out.push({ path: `${dir}/${entry.name}`, size: file.size, lastUsed: file.lastModified });
      } catch {
        // Removed meanwhile.
      }
    }
    return out;
  }

  private async dir(name: string, create: boolean): Promise<FileSystemDirectoryHandle | null> {
    this.rootDir ??= navigator.storage.getDirectory().then((d) => d.getDirectoryHandle(this.root, { create: true }));
    try {
      return await (await this.rootDir).getDirectoryHandle(name, { create });
    } catch (e) {
      if (!create && isNotFound(e)) return null;
      throw e;
    }
  }

  private async file(path: string, create: boolean): Promise<FileSystemFileHandle | null> {
    const [dir, name] = split(path);
    const folder = await this.dir(dir, create);
    if (!folder) return null;
    try {
      return await folder.getFileHandle(name, { create });
    } catch (e) {
      if (!create && isNotFound(e)) return null;
      throw e;
    }
  }
}

function split(path: string): [string, string] {
  const slash = path.indexOf("/");
  return [path.slice(0, slash), path.slice(slash + 1)];
}

const isNotFound = (e: unknown) => e instanceof DOMException && (e.name === "NotFoundError" || e.name === "TypeMismatchError");
