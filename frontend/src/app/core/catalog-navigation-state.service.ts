import { Injectable } from '@angular/core';

import { CatalogCollection, CatalogMovie, CatalogSort } from './catalog-api.service';

export interface CatalogNavigationSnapshot {
  query: string;
  lastSubmittedQuery: string;
  activeFilter: CatalogCollection;
  sortBy: CatalogSort;
  movies: CatalogMovie[];
  unavailablePosterIds: number[];
  currentPage: number;
  hasMoreMovies: boolean;
  scrollY: number;
}

@Injectable({ providedIn: 'root' })
export class CatalogNavigationStateService {
  private snapshot: CatalogNavigationSnapshot | null = null;

  save(snapshot: CatalogNavigationSnapshot): void {
    this.snapshot = snapshot;
  }

  restoreFor(query: string): CatalogNavigationSnapshot | null {
    const snapshot = this.snapshot;
    return snapshot?.query === query ? snapshot : null;
  }
}
