import { Store } from "../store";

/**
 * Notices a newer version of the page (the apps' new-version check, 1.9.2): the site publishes `build.json` next to
 * the page with every deploy; it's read on load, every few hours, and when the tab comes back into view. A page can't
 * update itself under a playing video, so it offers to reload.
 */

const EVERY_MS = 3 * 60 * 60 * 1000;

/** The newer version's number once one is out, else null. */
export const newVersion = new Store<string | null>(null);

export function watchForUpdates(current: { version: string; built: string }): () => void {
  if (import.meta.env.DEV) return () => {};
  const check = async () => {
    try {
      const response = await fetch(`${import.meta.env.BASE_URL}build.json`, { cache: "no-store" });
      if (!response.ok) return;
      const latest = (await response.json()) as { version: string; built: string };
      if (latest.built !== current.built) newVersion.set(latest.version);
    } catch {
      // Offline: try again later.
    }
  };
  const timer = setInterval(check, EVERY_MS);
  const onVisible = () => {
    if (document.visibilityState === "visible") void check();
  };
  document.addEventListener("visibilitychange", onVisible);
  void check();
  return () => {
    clearInterval(timer);
    document.removeEventListener("visibilitychange", onVisible);
  };
}
