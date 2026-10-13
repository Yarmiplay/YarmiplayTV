/**
 * The link YarmiplayServerTV's control panel shows under "Watch in a browser":
 * `https://tv.yarmiplay.com/watch/#join=<host>:<port>/<room, URL-encoded>[&lan=<LAN name>]`.
 */
export interface JoinLink {
  host: string;
  port: number;
  room: string;
  lanName: string | null;
}

export function parseJoinLink(hash: string): JoinLink | null {
  const params = new URLSearchParams(hash.replace(/^#/, ""));
  const join = params.get("join");
  if (!join) return null;
  // URLSearchParams has already decoded the value once; the room was encoded on its own, so split before the room.
  const raw = /(?:^|[#&])join=([^&]*)/.exec(hash)?.[1] ?? "";
  const match = /^(\[[^\]]+\]|[^:/]+):(\d{1,5})\/(.*)$/.exec(raw);
  if (!match) return null;
  const port = Number(match[2]);
  if (port < 1 || port > 65535) return null;
  let room: string;
  try {
    room = decodeURIComponent(match[3]);
  } catch {
    room = match[3];
  }
  const host = match[1].replace(/^\[|\]$/g, "");
  return { host, port, room: room || "lobby", lanName: params.get("lan")?.trim() || null };
}

export const joinLink = (l: JoinLink, base = "https://tv.yarmiplay.com/watch/"): string =>
  `${base}#join=${l.host.includes(":") ? `[${l.host}]` : l.host}:${l.port}/${encodeURIComponent(l.room)}${l.lanName ? `&lan=${l.lanName}` : ""}`;
