import { blockedAsMixedContent, type JellyfinClient, type JellyfinSession } from "../media";
import type { SharedJellyfin } from "../syncplay/yarmiplay";

/**
 * Adds the Jellyfin a YarmiplayServerTV host shares (HostJellyfinSharing.kt): finds an address that answers as that
 * server, signs in with Quick Connect (the Syncplay server approves the code for its guest account) and saves it as
 * shared by the host.
 *
 * In a browser, only the Syncplay server's own address usually works: the host's direct Jellyfin addresses are plain
 * http on the home network, which an https page may not load, so those are left out.
 */

/** What the room's server says about the host's Jellyfin, on one extension session. */
export interface JellyfinOffer {
  shared: SharedJellyfin;
  /** The capability is on. */
  on: boolean;
  /** The Syncplay server's https base (the extension session's), which proxies Jellyfin when [shared.proxy]. */
  baseUrl: string;
  /** The Syncplay server's name, for "(via …)". */
  via: string;
}

/** The server's answer to a Quick Connect authorization; null when it didn't answer. */
export type AuthorizeResult = { ok: boolean; error: string | null } | null;

export interface HostJellyfinDeps {
  jellyfin: Pick<JellyfinClient, "normalizeServerUrl" | "publicInfo" | "quickConnectEnabled" | "quickConnect" | "validate">;
  /** The saved Jellyfin servers. */
  saved(): JellyfinSession[];
  /** Settings' "Add media servers shared by Syncplay hosts". */
  enabled(): boolean;
  save(session: JellyfinSession): void;
  /** Sends the Quick Connect code to the Syncplay server for its guest account. */
  authorize(code: string): Promise<AuthorizeResult>;
  notify(text: string, isError?: boolean): void;
  log?(text: string): void;
  pageProtocol?: () => string;
  /** How long Quick Connect may take once the code is sent. */
  signInTimeoutMs?: number;
}

/** The Syncplay server's own address first when it proxies Jellyfin, then the host's addresses this page may load. */
export function candidates(shared: SharedJellyfin, baseUrl: string, pageProtocol: string): string[] {
  const all = [...(shared.proxy ? [baseUrl] : []), ...shared.addresses];
  return [...new Set(all)].filter((url) => !blockedAsMixedContent(withScheme(url), pageProtocol));
}

const withScheme = (url: string) => (/^https?:\/\//i.test(url) ? url : `http://${url}`);

export class HostJellyfinSharing {
  /** Server ids already handled on the current extension session, so a re-sent `jellyfin` message doesn't ask again. */
  private readonly handled = new Set<string>();
  private handledFor: unknown = null;
  private running: AbortController | null = null;

  constructor(private readonly deps: HostJellyfinDeps) {}

  /**
   * The room's offer changed. [sessionKey] identifies the extension session (a new one, after a reconnect, may ask
   * again). Resolves once it's been dealt with; a newer offer cancels this one.
   */
  async offer(sessionKey: unknown, offer: JellyfinOffer): Promise<void> {
    if (this.handledFor !== sessionKey) {
      this.handled.clear();
      this.handledFor = sessionKey;
    }
    const { shared } = offer;
    if (!offer.on || !shared.available || shared.serverId === "" || !this.deps.enabled()) return;
    if (this.handled.has(shared.serverId)) return;
    this.handled.add(shared.serverId);
    this.running?.abort();
    const running = new AbortController();
    this.running = running;
    try {
      await this.add(offer, running.signal);
    } catch (e) {
      this.handled.delete(shared.serverId);
      if (!running.signal.aborted) this.deps.log?.(`Couldn't add the host's Jellyfin: ${(e as Error).message}`);
    } finally {
      if (this.running === running) this.running = null;
    }
  }

  /** The room was left: stop any sign-in under way. */
  stop() {
    this.running?.abort();
    this.running = null;
  }

  private async add(offer: JellyfinOffer, signal: AbortSignal): Promise<void> {
    const { shared } = offer;
    const { jellyfin } = this.deps;
    const urls = candidates(shared, offer.baseUrl, this.deps.pageProtocol?.() ?? currentProtocol());
    const existing = this.deps.saved().find((s) => s.serverId === shared.serverId);
    if (existing && !existing.noLongerShared) {
      const valid = await jellyfin.validate(existing).catch(() => null);
      if (valid === true) {
        if (existing.sharedBy !== "" && !sameList(existing.candidateUrls, urls)) this.deps.save({ ...existing, candidateUrls: urls });
        return;
      }
      if (valid === null && existing.sharedBy !== "") {
        // Its address doesn't answer from here; another one of the host's may.
        const url = await this.probe(urls, shared.serverId);
        if (url === null) return;
        const moved = { ...existing, serverUrl: url, candidateUrls: urls };
        if (await jellyfin.validate(moved).catch(() => false)) this.deps.save(moved);
        return;
      }
      // A server the user signed in to themselves stays theirs, even with an expired sign-in.
      if (existing.sharedBy === "") return;
    }
    const url = await this.probe(urls, shared.serverId);
    if (url === null) {
      this.deps.log?.(`None of the host's Jellyfin addresses answered as server ${shared.serverId}`);
      return;
    }
    if (signal.aborted) return;
    if (!(await jellyfin.quickConnectEnabled(url))) {
      this.deps.log?.("The host's Jellyfin has Quick Connect switched off");
      return;
    }
    const attempt = new AbortController();
    const stop = () => attempt.abort();
    signal.addEventListener("abort", stop);
    const timer = setTimeout(stop, this.deps.signInTimeoutMs ?? 60_000);
    // Like the apps, a code the server turned down isn't asked for again on this session.
    let turnedDown = false;
    try {
      let session: JellyfinSession;
      try {
        session = await jellyfin.quickConnect(url, {
          signal: attempt.signal,
          onCode: (code) => {
            void this.deps.authorize(code).then((answer) => {
              if (answer?.ok) return;
              if (answer === null) this.deps.log?.("The Syncplay server didn't answer the Jellyfin authorization");
              else this.deps.notify(`Couldn't add the host's Jellyfin: ${answer.error ?? "the server refused"}`, true);
              turnedDown = true;
              attempt.abort();
            });
          },
        });
      } catch (e) {
        if (turnedDown) return;
        throw e;
      }
      const name = shared.serverName.trim() || session.serverName;
      this.deps.save({ ...session, serverName: `${name} (via ${offer.via})`, sharedBy: offer.via, candidateUrls: urls, noLongerShared: false });
      this.deps.notify(`Added ${name}, shared by the Syncplay host`);
    } finally {
      clearTimeout(timer);
      signal.removeEventListener("abort", stop);
    }
  }

  /** The first of [urls] that answers as Jellyfin server [serverId]. */
  private async probe(urls: string[], serverId: string): Promise<string | null> {
    for (const url of urls) {
      let base: string;
      try {
        base = this.deps.jellyfin.normalizeServerUrl(url);
      } catch {
        continue;
      }
      const info = await this.deps.jellyfin.publicInfo(base).catch(() => null);
      if (info?.Id === serverId) return base;
    }
    return null;
  }
}

const sameList = (a: string[], b: string[]) => a.length === b.length && a.every((x, i) => x === b[i]);

const currentProtocol = (): string => (globalThis as { location?: { protocol?: string } }).location?.protocol ?? "";
