/**
 * Rooms joined before (SettingsStore.kt's SavedRoom and SavedRooms): the recent ones, kept automatically (the newest
 * MAX_RECENT), and the ones the user saved, under a label if they named them.
 */
export interface SavedRoom {
  host: string;
  port: number;
  useTls: boolean;
  username: string;
  room: string;
  password: string;
  label: string;
  saved: boolean;
  lastUsedMillis: number;
  /**
   * Skip openings and endings in this room: what this device last chose there. Joining it when saved turns the room's
   * shared setting on; on servers without shared room settings it is this device's setting for the room.
   */
  autoSkip: boolean;
  /** The web's own: the server's LAN name, tried before [host] on the next join (see the plan's LAN race). */
  lanName?: string;
}

/** How many recent (not saved) rooms are kept. */
export const MAX_RECENT = 15;

export const room = (r: Partial<SavedRoom> & Pick<SavedRoom, "host" | "port" | "username" | "room">): SavedRoom => ({
  useTls: true,
  password: "",
  label: "",
  saved: false,
  lastUsedMillis: 0,
  autoSkip: false,
  ...r,
});

/** Identifies the room: the same room on the same server is one entry. */
export const keyOf = (r: Pick<SavedRoom, "host" | "port" | "room">): string => `${r.host.trim().toLowerCase()}:${r.port}/${r.room}`;

const clean = (value: string) => value.replace(/[\t\n]/g, " ");

/**
 * The `sp_saved_rooms` format: one room per line, tab-separated fields (tabs and newlines in values become spaces).
 * Fields added later go at the end, so older lines still read; the LAN name is the web's, after the apps' fields.
 */
export function decode(text: string): SavedRoom[] {
  const out: SavedRoom[] = [];
  for (const line of text.split(/\r?\n/)) {
    const f = line.split("\t");
    if (f.length < 9) continue;
    const port = Number(f[1]);
    if (!/^-?\d+$/.test(f[1]) || !Number.isFinite(port)) continue;
    const used = /^-?\d+$/.test(f[8]) ? Number(f[8]) : 0;
    out.push({
      host: f[0],
      port,
      useTls: f[2] === "1",
      username: f[3],
      room: f[4],
      password: f[5],
      label: f[6],
      saved: f[7] === "1",
      lastUsedMillis: used,
      autoSkip: f[9] === "1",
      ...(f[10] ? { lanName: f[10] } : {}),
    });
  }
  return out;
}

export function encode(rooms: SavedRoom[]): string {
  return rooms
    .map((r) =>
      [
        r.host,
        String(r.port),
        r.useTls ? "1" : "0",
        r.username,
        r.room,
        r.password,
        r.label,
        r.saved ? "1" : "0",
        String(r.lastUsedMillis),
        r.autoSkip ? "1" : "0",
        ...(r.lanName ? [r.lanName] : []),
      ]
        .map(clean)
        .join("\t"),
    )
    .join("\n");
}

/** Saved rooms first, then recents, each newest first; recents beyond MAX_RECENT are dropped. */
export function normalize(rooms: SavedRoom[]): SavedRoom[] {
  const newest = new Map<string, SavedRoom>();
  for (const r of rooms) {
    const known = newest.get(keyOf(r));
    if (!known || r.lastUsedMillis > known.lastUsedMillis) newest.set(keyOf(r), r);
  }
  const unique = [...newest.values()];
  const byUse = (a: SavedRoom, b: SavedRoom) => b.lastUsedMillis - a.lastUsedMillis;
  const saved = unique.filter((r) => r.saved).sort(byUse);
  const recent = unique.filter((r) => !r.saved).sort(byUse).slice(0, MAX_RECENT);
  return [...saved, ...recent];
}

/** [rooms] after joining [visit]: a known room keeps its name, saved state and choices, a new one becomes a recent. */
export function visited(rooms: SavedRoom[], visit: SavedRoom): SavedRoom[] {
  const key = keyOf(visit);
  const known = rooms.find((r) => keyOf(r) === key);
  const updated = known
    ? {
        ...known,
        host: visit.host,
        useTls: visit.useTls,
        username: visit.username,
        password: visit.password,
        lastUsedMillis: visit.lastUsedMillis,
        lanName: visit.lanName ?? known.lanName,
      }
    : visit;
  return normalize([...rooms.filter((r) => keyOf(r) !== key), updated]);
}

/** Keeps the room [key] (with [label]) among the saved rooms, or with [saved] false makes it a recent one again. */
export const setSaved = (rooms: SavedRoom[], key: string, saved: boolean, label = ""): SavedRoom[] =>
  normalize(rooms.map((r) => (keyOf(r) === key ? { ...r, saved, label: saved ? label.trim() : "" } : r)));

/** Adds [newRoom] to the saved rooms (e.g. "Save this room" on a room not joined yet). */
export function save(rooms: SavedRoom[], newRoom: SavedRoom): SavedRoom[] {
  const key = keyOf(newRoom);
  const known = rooms.find((r) => keyOf(r) === key);
  const kept = known
    ? { ...known, saved: true, label: newRoom.label.trim() ? newRoom.label : known.label, autoSkip: known.autoSkip || newRoom.autoSkip }
    : { ...newRoom, saved: true };
  return normalize([...rooms.filter((r) => keyOf(r) !== key), kept]);
}

/** Remembers [on] as this device's choice for skipping openings and endings in room [key]. */
export const setAutoSkip = (rooms: SavedRoom[], key: string, on: boolean): SavedRoom[] =>
  rooms.map((r) => (keyOf(r) === key ? { ...r, autoSkip: on } : r));

export const forget = (rooms: SavedRoom[], key: string): SavedRoom[] => rooms.filter((r) => keyOf(r) !== key);
