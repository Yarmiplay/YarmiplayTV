/**
 * What MyAnimeList matching needs to know about an episode in the library (the apps' `EpisodeInfo`, read from a file
 * name or a media server): the show's name, its season and episode as the library numbers them, and a key that stays
 * the same across the show's episodes. Kept here, small, so matching doesn't depend on how file names are read.
 */
export interface EpisodeInfo {
  /** The show's name (the apps' `seriesName`). */
  title: string;
  season: number | null;
  episode: number | null;
  year?: number | null;
  /** Identifies the show across its episodes: the server's series id, or the normalized title for files. */
  seriesKey: string;
  /** The MyAnimeList id when the media server knows it (anime metadata plugins). */
  malId?: number | null;
}

/** The same episode of the same show (the Kotlin data class's equality). */
export function sameEpisode(a: EpisodeInfo | null | undefined, b: EpisodeInfo | null | undefined): boolean {
  if (!a || !b) return !a && !b;
  return (
    a.title === b.title &&
    (a.season ?? null) === (b.season ?? null) &&
    (a.episode ?? null) === (b.episode ?? null) &&
    (a.year ?? null) === (b.year ?? null) &&
    a.seriesKey === b.seriesKey &&
    (a.malId ?? null) === (b.malId ?? null)
  );
}
