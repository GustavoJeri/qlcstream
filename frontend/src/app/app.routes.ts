import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: 'setup', loadComponent: () => import('./pages/setup.page').then((m) => m.SetupPage) },
  { path: 'catalog', loadComponent: () => import('./pages/catalog.page').then((m) => m.CatalogPage) },
  { path: 'catalog/:tmdbId', loadComponent: () => import('./pages/movie-details.page').then((m) => m.MovieDetailsPage) },
  { path: 'series', loadComponent: () => import('./pages/series.page').then((m) => m.SeriesPage) },
  { path: 'series/:tmdbId', loadComponent: () => import('./pages/series-details.page').then((m) => m.SeriesDetailsPage) },
  { path: 'people/:tmdbId', loadComponent: () => import('./pages/person-details.page').then((m) => m.PersonDetailsPage) },
  { path: 'downloads', loadComponent: () => import('./pages/downloads.page').then((m) => m.DownloadsPage) },
  { path: 'library/collections/:collectionId', loadComponent: () => import('./pages/collection-details.page').then((m) => m.CollectionDetailsPage) },
  { path: 'library', loadComponent: () => import('./pages/library.page').then((m) => m.LibraryPage) },
  { path: 'settings', loadComponent: () => import('./pages/settings.page').then((m) => m.SettingsPage) },
  { path: '', pathMatch: 'full', redirectTo: 'catalog' },
  { path: '**', redirectTo: 'catalog' },
];
