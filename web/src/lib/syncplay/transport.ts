/**
 * The connection to YarmiplayServerTV: a WebSocket on its Syncplay port (`/yarmiplay/ws`), one JSON line per text
 * message. Browsers can't open raw TCP, so the web client only reaches servers that take WebSockets; with a DuckDNS
 * certificate it's `wss://`, which is also the only kind an https page may open.
 */
export interface Transport {
  /** Whether the connection is encrypted (wss://). */
  readonly tls: boolean;
  /** The name dialled. */
  readonly host: string;
  send(line: string): void;
  close(): void;
  onLine: (line: string) => void;
  /** Once, when the connection ends; [reason] for the log. */
  onClose: (reason: string) => void;
}

/** Opens a transport to [host]:[port]; resolves once it's open, rejects when it can't be. */
export type TransportFactory = (host: string, port: number, tls: boolean) => Promise<Transport>;

/** [host] for a URL: IPv6 literals in brackets. */
export const urlHost = (host: string): string => {
  const h = host.trim();
  return h.includes(":") && !h.startsWith("[") ? `[${h}]` : h;
};

/**
 * Whether [host] is this computer. Browsers let even an https page talk to it unencrypted (the traffic never leaves
 * the machine), so a server on the same computer is reached at ws://localhost: its certificate is for its DuckDNS
 * name, and its own public address is often out of reach from inside the home.
 */
export const isLoopbackHost = (host: string): boolean => /^(localhost|127\.\d+\.\d+\.\d+|\[?::1\]?)$/i.test(host.trim());

export const webSocketUrl = (host: string, port: number, tls: boolean): string =>
  `${tls ? "wss" : "ws"}://${urlHost(host)}:${port}/yarmiplay/ws`;

/** How long a WebSocket may take to open before it counts as unreachable. */
const OPEN_TIMEOUT_MS = 10_000;

export const openWebSocket: TransportFactory = (host, port, tls) =>
  new Promise((resolve, reject) => {
    let socket: WebSocket;
    try {
      socket = new WebSocket(webSocketUrl(host, port, tls));
    } catch (e) {
      reject(e instanceof Error ? e : new Error(String(e)));
      return;
    }
    let opened = false;
    let closed = false;
    const timer = setTimeout(() => {
      if (!opened) {
        socket.close();
        reject(new Error(`${host} didn't answer`));
      }
    }, OPEN_TIMEOUT_MS);
    const transport: Transport = {
      tls,
      host,
      send: (line) => {
        if (socket.readyState === WebSocket.OPEN) socket.send(line);
      },
      close: () => socket.close(),
      onLine: () => {},
      onClose: () => {},
    };
    socket.onopen = () => {
      opened = true;
      clearTimeout(timer);
      resolve(transport);
    };
    socket.onmessage = (event) => {
      if (typeof event.data !== "string") return;
      // A message may hold one line, or several from a server that batches them.
      for (const line of event.data.split(/\r?\n/)) if (line.trim()) transport.onLine(line);
    };
    socket.onclose = (event) => {
      clearTimeout(timer);
      if (!opened) {
        reject(new Error(event.code === 1006 ? `Couldn't reach ${host}` : `${host} refused the connection (${event.code})`));
        return;
      }
      if (closed) return;
      closed = true;
      transport.onClose(event.reason || (event.code === 1000 ? "Server closed the connection" : `Connection lost (${event.code})`));
    };
    socket.onerror = () => {
      // onclose follows with the details.
    };
  });

/** A way to the server; [atHome] for the LAN name, which only counts with TLS. */
export interface Route {
  host: string;
  port: number;
  delayMs: number;
  atHome: boolean;
}

/** How long the public name has to answer before the LAN name is tried too (as LanRoutes.kt's head start). */
export const LAN_HEAD_START_MS = 300;

/**
 * Opens [routes], each after its delay, and returns the first that opens (the others are closed). The public name
 * goes first and the LAN name joins after a moment: at home through a router that loops its public address back in,
 * the public name answers at once and the browser never asks for local network access.
 */
export async function firstOpen(
  routes: Route[],
  tls: boolean,
  factory: TransportFactory,
): Promise<{ route: Route; transport: Transport } | { errors: Map<Route, Error> }> {
  const errors = new Map<Route, Error>();
  return new Promise((resolve) => {
    let done = false;
    let left = routes.length;
    if (left === 0) resolve({ errors });
    const timers: ReturnType<typeof setTimeout>[] = [];
    for (const route of routes) {
      timers.push(
        setTimeout(() => {
          if (done) return finish();
          factory(route.host, route.port, tls).then(
            (transport) => {
              if (done) transport.close();
              else {
                done = true;
                timers.forEach(clearTimeout);
                resolve({ route, transport });
              }
              finish();
            },
            (error: Error) => {
              errors.set(route, error);
              finish();
            },
          );
        }, route.delayMs),
      );
    }
    function finish() {
      left--;
      if (left === 0 && !done) {
        done = true;
        resolve({ errors });
      }
    }
  });
}
