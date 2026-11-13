import { Injectable, signal } from '@angular/core';

export interface RecentTitle { mediaType: 'MOVIE' | 'SERIES'; tmdbId: number; title: string; subtitle: string; posterPath: string | null; }

@Injectable({ providedIn: 'root' })
export class SearchHistoryService {
  private readonly searchKey = 'qlc-stream.search-history';
  private readonly recentKey = 'qlc-stream.recent-titles';
  readonly searches = signal<string[]>(this.read<string[]>(this.searchKey, []));
  readonly recentTitles = signal<RecentTitle[]>(this.read<RecentTitle[]>(this.recentKey, []));

  rememberSearch(query: string): void {
    const value = query.replace(/\s+/g, ' ').trim();
    if (value.length < 2) return;
    this.searches.update((current) => this.save(this.searchKey, [value, ...current.filter((item) => item.localeCompare(value, 'pt-BR', { sensitivity: 'accent' }) !== 0)].slice(0, 8)));
  }

  rememberTitle(title: RecentTitle): void {
    this.recentTitles.update((current) => this.save(this.recentKey, [title, ...current.filter((item) => !(item.mediaType === title.mediaType && item.tmdbId === title.tmdbId))].slice(0, 6)));
  }

  clearSearches(): void { this.searches.set(this.save(this.searchKey, [])); }
  clearRecentTitles(): void { this.recentTitles.set(this.save(this.recentKey, [])); }

  private read<T>(key: string, fallback: T): T { try { return JSON.parse(localStorage.getItem(key) ?? '') as T; } catch { return fallback; } }
  private save<T>(key: string, value: T): T { localStorage.setItem(key, JSON.stringify(value)); return value; }
}
