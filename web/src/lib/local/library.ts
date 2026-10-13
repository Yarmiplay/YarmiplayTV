import { baseName } from "../syncplay/filenames";

/**
 * Videos on this device (FileLocalLibrary.kt and LocalMatcher.kt for the browser): media folders the user picked
 * (kept between visits where the browser allows it, as directory handles in IndexedDB), and files opened or dropped
 * this session. A playlist entry is found here first, by its file name, as desktop Syncplay searches its media
 * directories.
 */

export interface LocalFile {
  name: string;
  size: number;
  /** Last modified, in milliseconds since the epoch; 0 when unknown. */
  modified: number;
  /** Where it is under the media folder, like "Show/Season 1"; empty for files directly in it. */
  directory: string;
  folder: string;
  /** Reads the file; folder files are opened when needed, so a long list costs nothing. */
  open(): Promise<File>;
}

const VIDEO_EXTENSIONS = new Set([
  "mkv", "mp4", "m4v", "avi", "mov", "webm", "wmv", "flv", "ts", "m2ts", "mts", "mpg", "mpeg", "ogv", "3gp", "vob",
]);

export const isVideo = (name: string, mimeType?: string) =>
  mimeType?.startsWith("video/") === true || VIDEO_EXTENSIONS.has(name.slice(name.lastIndexOf(".") + 1).toLowerCase());


/** Syncplay's stripfilename, lowercased: the looser match after exact names. */
export function normalize(name: string): string {
  let decoded = name;
  try {
    decoded = decodeURIComponent(name);
  } catch {
    // As it is.
  }
  return baseName(decoded).replace(/[-~_.[\](): ]/g, "").toLowerCase();
}

export type MatchKind = "EXACT_FILENAME" | "NORMALIZED_FILENAME";

/** The local file for a shared-playlist entry: the exact name, the name ignoring case, then normalized. */
export function find(files: LocalFile[], fileName: string): { file: LocalFile; kind: MatchKind } | null {
  const wanted = baseName(fileName);
  const exact = files.find((f) => f.name === wanted) ?? files.find((f) => f.name.toLowerCase() === wanted.toLowerCase());
  if (exact) return { file: exact, kind: "EXACT_FILENAME" };
  const normalized = normalize(wanted);
  if (!normalized) return null;
  const loose = files.find((f) => normalize(f.name) === normalized);
  return loose ? { file: loose, kind: "NORMALIZED_FILENAME" } : null;
}

// --- Folder handles kept in IndexedDB ----------------------------------------------------------

type DirectoryHandle = FileSystemDirectoryHandle & {
  values(): AsyncIterable<FileSystemHandle>;
  queryPermission?(opts: { mode: "read" }): Promise<PermissionState>;
  requestPermission?(opts: { mode: "read" }): Promise<PermissionState>;
};

const DB = "yarmiplaytv-files";
const STORE = "folders";

function db(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function storeRequest<T>(mode: IDBTransactionMode, run: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const d = await db();
  try {
    return await new Promise<T>((resolve, reject) => {
      const r = run(d.transaction(STORE, mode).objectStore(STORE));
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  } finally {
    d.close();
  }
}

export interface MediaFolder {
  name: string;
  /** Whether the browser lets the page read it now; false until the user allows it again (once per visit). */
  readable: boolean;
  handle?: DirectoryHandle;
  /** Folders picked with <input webkitdirectory> (browsers without folder handles), for this visit only. */
  files?: File[];
}

/** Whether this browser can keep media folders between visits (Chromium's File System Access API). */
export const keepsFolders = () => typeof (globalThis as { showDirectoryPicker?: unknown }).showDirectoryPicker === "function";

export class LocalLibrary {
  private folders: MediaFolder[] = [];
  private folderFiles: LocalFile[] = [];
  private readonly opened = new Map<string, File>();
  private readonly listeners = new Set<() => void>();

  /** Every video known here: opened files first, then the media folders'. */
  get files(): LocalFile[] {
    const opened = [...this.opened.values()].map((f) => fileEntry(f, "", "Opened"));
    return [...opened, ...this.folderFiles];
  }

  get mediaFolders(): MediaFolder[] {
    return this.folders;
  }

  get hasFolders(): boolean {
    return this.folders.length > 0;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private changed() {
    for (const l of this.listeners) l();
  }

  /** The folders kept from earlier visits; the ones still allowed are scanned. */
  async restore(): Promise<void> {
    if (!keepsFolders()) return;
    const handles = (await storeRequest<DirectoryHandle[] | undefined>("readonly", (s) => s.get("folders")).catch(() => undefined)) ?? [];
    this.folders = await Promise.all(
      handles.map(async (handle) => ({ name: handle.name, handle, readable: (await handle.queryPermission?.({ mode: "read" })) === "granted" })),
    );
    await this.rescan();
  }

  /** Asks the browser to let the page read the kept folders again (needs a click). */
  async allowAgain(): Promise<void> {
    for (const folder of this.folders) {
      if (folder.handle && !folder.readable) folder.readable = (await folder.handle.requestPermission?.({ mode: "read" })) === "granted";
    }
    await this.rescan();
  }

  /** Picks a media folder with the browser's folder picker (kept for next time where possible). */
  async addFolder(): Promise<void> {
    const picker = (globalThis as unknown as { showDirectoryPicker(opts?: object): Promise<DirectoryHandle> }).showDirectoryPicker;
    const handle = await picker({ id: "media", mode: "read" });
    if (this.folders.some((f) => f.handle && f.name === handle.name)) {
      for (const f of this.folders) if (f.handle && (await f.handle.isSameEntry(handle))) return;
    }
    this.folders = [...this.folders, { name: handle.name, handle, readable: true }];
    await this.persist();
    await this.rescan();
  }

  /** A folder picked with <input webkitdirectory>: [files] carry their paths in webkitRelativePath. */
  async addFolderFiles(files: File[]): Promise<void> {
    const name = files[0]?.webkitRelativePath.split("/")[0] || "Folder";
    this.folders = [...this.folders, { name, readable: true, files: files.filter((f) => isVideo(f.name, f.type)) }];
    await this.rescan();
  }

  async removeFolder(index: number): Promise<void> {
    this.folders = this.folders.filter((_, i) => i !== index);
    await this.persist();
    await this.rescan();
  }

  /** A file opened or dropped on the page; it's remembered by name until the page closes. */
  remember(file: File): LocalFile {
    this.opened.set(file.name, file);
    this.changed();
    return fileEntry(file, "", "Opened");
  }

  find(fileName: string) {
    return find(this.files, fileName);
  }

  private async persist() {
    if (!keepsFolders()) return;
    const handles = this.folders.flatMap((f) => (f.handle ? [f.handle] : []));
    await storeRequest("readwrite", (s) => s.put(handles, "folders")).catch(() => {});
  }

  private async rescan() {
    const out: LocalFile[] = [];
    for (const folder of this.folders) {
      if (folder.files) {
        for (const f of folder.files) {
          const parts = f.webkitRelativePath.split("/");
          out.push(fileEntry(f, parts.slice(1, -1).join("/"), folder.name));
        }
      } else if (folder.handle && folder.readable) {
        await walk(folder.handle, "", folder.name, out).catch(() => {});
      }
    }
    this.folderFiles = out;
    this.changed();
  }
}

function fileEntry(file: File, directory: string, folder: string): LocalFile {
  return { name: file.name, size: file.size, modified: file.lastModified, directory, folder, open: async () => file };
}

/** Every video under [dir], with its size and date (the relay offers files by size and a hash of their ends). */
async function walk(dir: DirectoryHandle, path: string, folder: string, out: LocalFile[], depth = 0): Promise<void> {
  if (depth > 8) return;
  for await (const entry of dir.values()) {
    if (entry.kind === "directory") {
      await walk(entry as DirectoryHandle, path ? `${path}/${entry.name}` : entry.name, folder, out, depth + 1);
    } else if (isVideo(entry.name)) {
      const handle = entry as FileSystemFileHandle;
      const file = await handle.getFile();
      out.push({ name: entry.name, size: file.size, modified: file.lastModified, directory: path, folder, open: () => handle.getFile() });
    }
  }
}
