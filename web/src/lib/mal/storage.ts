import { Store } from "../store";
import type { MalMapping } from "./matcher";
import { compactUpdate, readAccount, readQueue, type MalAccount, type MalQueued } from "./models";

/** Where MyAnimeList's things are kept: `localStorage`'s three methods, so the app can back it with any storage. */
export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/**
 * [storage] (`localStorage` by default) under [prefix], as the web version's settings are kept. Storage can be
 * refused (private windows) or cleared: reads then find nothing and writes are kept only until the page closes.
 */
export function prefixedStorage(prefix = "ytv:", storage: () => Storage = () => localStorage): KeyValueStorage {
  const memory = new Map<string, string>();
  return {
    getItem(key) {
      try {
        return storage().getItem(prefix + key);
      } catch {
        return memory.get(key) ?? null;
      }
    },
    setItem(key, value) {
      try {
        storage().setItem(prefix + key, value);
      } catch {
        memory.set(key, value);
      }
    },
    removeItem(key) {
      memory.delete(key);
      try {
        storage().removeItem(prefix + key);
      } catch {
        // Nothing kept there.
      }
    },
  };
}

/** Storage in memory only (tests, or a page that keeps nothing). */
export function memoryStorage(): KeyValueStorage & { readonly items: Map<string, string> } {
  const items = new Map<string, string>();
  return {
    items,
    getItem: (key) => items.get(key) ?? null,
    setItem: (key, value) => void items.set(key, value),
    removeItem: (key) => void items.delete(key),
  };
}

/** The apps' keys (SettingsStore.kt): everything MyAnimeList is under `mal_*`, on this device only. */
export const MalKeys = {
  account: "mal_account",
  auto: "mal_auto",
  ask: "mal_ask",
  mappings: "mal_mappings",
  ignored: "mal_ignored",
  ignoredNames: "mal_ignored_names",
  queue: "mal_queue",
} as const;

const ALL_KEYS = Object.values(MalKeys);

/** What's kept for MyAnimeList, as the apps' settings have it. */
export interface MalSettings {
  account: MalAccount | null;
  /** Update the list by itself when an episode has been watched. */
  autoUpdate: boolean;
  /** Ask before each automatic update. */
  askFirst: boolean;
  /** Shows matched to a MyAnimeList entry by hand (or surely), by [MalService.mappingKey]. */
  mappings: Record<string, MalMapping>;
  /** Shows not to track, by series key. */
  ignored: string[];
  /** The shows' names for [ignored], to list them. */
  ignoredNames: Record<string, string>;
  /** List updates waiting to be sent. */
  queue: MalQueued[];
}

export const defaultMalSettings = (): MalSettings => ({
  account: null,
  autoUpdate: true,
  askFirst: false,
  mappings: {},
  ignored: [],
  ignoredNames: {},
  queue: [],
});

function parse(text: string | null): unknown {
  if (text == null) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((s): s is string => typeof s === "string") : []);

function readMappings(v: unknown): Record<string, MalMapping> {
  if (typeof v !== "object" || v === null || Array.isArray(v)) return {};
  const out: Record<string, MalMapping> = {};
  for (const [key, m] of Object.entries(v as Record<string, unknown>)) {
    if (typeof m !== "object" || m === null) continue;
    const { malId, offset } = m as Record<string, unknown>;
    if (typeof malId === "number") out[key] = { malId, offset: typeof offset === "number" ? offset : 0 };
  }
  return out;
}

/**
 * MyAnimeList's settings in [storage], under the apps' keys: the account (JSON), the two switches, the matches
 * (JSON by key), the ignored shows (a list, their names as `key<TAB>name`) and the queue (JSON). [settings] is the
 * current value, for screens; anything unreadable is the default.
 */
export class MalStore {
  readonly settings: Store<MalSettings>;

  constructor(private readonly storage: KeyValueStorage) {
    this.settings = new Store(this.read());
  }

  get value(): MalSettings {
    return this.settings.value;
  }

  private read(): MalSettings {
    const d = defaultMalSettings();
    const get = (key: string) => {
      try {
        return this.storage.getItem(key);
      } catch {
        return null;
      }
    };
    const bool = (key: string, fallback: boolean) => {
      const v = get(key);
      return v === "true" ? true : v === "false" ? false : fallback;
    };
    const names: Record<string, string> = {};
    for (const entry of strings(parse(get(MalKeys.ignoredNames)))) {
      const tab = entry.indexOf("\t");
      if (tab < 0) names[entry] = "";
      else names[entry.slice(0, tab)] = entry.slice(tab + 1);
    }
    return {
      account: readAccount(parse(get(MalKeys.account))),
      autoUpdate: bool(MalKeys.auto, d.autoUpdate),
      askFirst: bool(MalKeys.ask, d.askFirst),
      mappings: readMappings(parse(get(MalKeys.mappings))),
      ignored: strings(parse(get(MalKeys.ignored))),
      ignoredNames: names,
      queue: readQueue(parse(get(MalKeys.queue))),
    };
  }

  private write(key: string, value: string | null) {
    try {
      if (value === null) this.storage.removeItem(key);
      else this.storage.setItem(key, value);
    } catch {
      // Not kept past this page; everything still works until it closes.
    }
  }

  private change(fields: Partial<MalSettings>) {
    this.settings.set({ ...this.settings.value, ...fields });
  }

  saveAccount(account: MalAccount | null) {
    this.write(MalKeys.account, account ? JSON.stringify(account) : null);
    this.change({ account });
  }

  savePrefs(autoUpdate: boolean, askFirst: boolean) {
    this.write(MalKeys.auto, String(autoUpdate));
    this.write(MalKeys.ask, String(askFirst));
    this.change({ autoUpdate, askFirst });
  }

  saveMapping(key: string, mapping: MalMapping | null) {
    const mappings = { ...this.value.mappings };
    if (mapping) mappings[key] = mapping;
    else delete mappings[key];
    this.write(MalKeys.mappings, JSON.stringify(mappings));
    this.change({ mappings });
  }

  clearMappings() {
    this.write(MalKeys.mappings, null);
    this.change({ mappings: {} });
  }

  saveIgnored(seriesKeys: string[], names: Record<string, string> = {}) {
    const ignored = [...new Set(seriesKeys)];
    const kept: Record<string, string> = {};
    for (const [k, v] of Object.entries(names)) if (ignored.includes(k)) kept[k] = v.replaceAll("\t", " ");
    this.write(MalKeys.ignored, JSON.stringify(ignored));
    this.write(MalKeys.ignoredNames, JSON.stringify(Object.entries(kept).map(([k, v]) => `${k}\t${v}`)));
    this.change({ ignored, ignoredNames: kept });
  }

  saveQueue(queue: MalQueued[]) {
    const kept = queue.map((q) => ({ animeId: q.animeId, update: compactUpdate(q.update) }));
    this.write(MalKeys.queue, kept.length === 0 ? null : JSON.stringify(kept));
    this.change({ queue: kept });
  }

  /** Deletes everything kept for MyAnimeList. */
  clear() {
    for (const key of ALL_KEYS) this.write(key, null);
    this.settings.set(defaultMalSettings());
  }
}
