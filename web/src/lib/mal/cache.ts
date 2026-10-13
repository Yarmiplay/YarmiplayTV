import type { MalClient } from "./client";
import type { MalCatalog } from "./matcher";
import type { MalAnime, MalListStatus, MalStatus } from "./models";

const DAY_MILLIS = 24 * 60 * 60 * 1000;
const LIST_MILLIS = 10 * 60_000;

/**
 * Anime details from MyAnimeList, kept in memory for at most [ttlMillis] (24 hours: the API agreement's limit for
 * keeping its content); never written to storage.
 */
export class MalCache implements MalCatalog {
  private readonly entries = new Map<number, { anime: MalAnime; at: number }>();
  private readonly searches = new Map<string, { at: number; results: MalAnime[] }>();
  /** The user's list by status; refreshed after 10 minutes or any change made here. */
  private readonly lists = new Map<MalStatus, { at: number; entries: [MalAnime, MalListStatus][] }>();

  constructor(
    private readonly client: Pick<MalClient, "search" | "anime" | "myList">,
    private readonly now: () => number,
    private readonly ttlMillis = DAY_MILLIS,
  ) {}

  async search(query: string): Promise<MalAnime[]> {
    const key = query.trim().toLowerCase();
    const hit = this.searches.get(key);
    if (hit && this.now() - hit.at < this.ttlMillis) return hit.results;
    const results = await this.client.search(query);
    this.searches.set(key, { at: this.now(), results });
    return results;
  }

  async anime(id: number): Promise<MalAnime> {
    const hit = this.entries.get(id);
    if (hit && this.now() - hit.at < this.ttlMillis) return hit.anime;
    const anime = await this.client.anime(id);
    this.put(anime);
    return anime;
  }

  watching(): Promise<[MalAnime, MalListStatus][]> {
    return this.list("watching");
  }

  completed(): Promise<[MalAnime, MalListStatus][]> {
    return this.list("completed");
  }

  /** Your list entries with [status], newest change first; [fresh] reads them again. */
  async list(status: MalStatus, fresh = false): Promise<[MalAnime, MalListStatus][]> {
    const hit = this.lists.get(status);
    if (!fresh && hit && this.now() - hit.at < LIST_MILLIS) return hit.entries;
    const entries = await this.client.myList(status);
    this.lists.set(status, { at: this.now(), entries });
    return entries;
  }

  put(anime: MalAnime) {
    this.entries.set(anime.id, { anime, at: this.now() });
    this.lists.clear();
  }

  forget(id: number) {
    this.entries.delete(id);
    this.lists.clear();
  }

  clear() {
    this.entries.clear();
    this.searches.clear();
    this.lists.clear();
  }
}
