import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { tmdbImage } from '../core/tmdb-image';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map, switchMap } from 'rxjs';

import { FavoriteItem, LibraryApiService, LibraryCollection } from '../core/library-api.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-collection-details-page',
  imports: [RouterLink],
  styleUrl: './collection-details.page.scss',
  template: `
    <div class="page collection-details-page">
      <a class="collection-back" routerLink="/library"><i class="ph ph-arrow-left"></i>Biblioteca</a>
      @if (state() === 'loading') { <section class="collection-loading"><i></i><i></i><i></i><i></i></section> }
      @else if (collection(); as current) {
        <header class="collection-hero"><div class="collection-hero-copy"><p class="collection-kicker">Coleção</p><h1>{{ current.name }}</h1><p class="collection-summary">{{ current.items.length }} título{{ current.items.length === 1 ? '' : 's' }} escolhido{{ current.items.length === 1 ? '' : 's' }} para esta lista.</p><div class="collection-hero-actions"><a class="btn btn-quiet" routerLink="/library">Biblioteca</a><button class="collection-delete" type="button" (click)="deleteCollection(current)"><i class="ph ph-trash"></i>Excluir</button></div></div>@if (current.items.length) { <div class="collection-cover-stack" aria-hidden="true">@for (cover of current.items.slice(0, 4); track cover.mediaType + cover.tmdbId; let index = $index) { @if (cover.posterPath) { <img [src]="posterUrl(cover)" alt="" [style.--cover-index]="index" /> } @else { <span [style.--cover-index]="index"><i class="ph ph-film-strip"></i></span> } }</div> }</header>
        @if (current.items.length) { <section class="collection-titles"><div class="collection-titles-heading"><h2>Títulos guardados</h2><span>{{ current.items.length }}</span></div><section class="collection-title-grid" aria-label="Títulos da coleção">@for (item of current.items; track item.mediaType + item.tmdbId) { <article class="collection-title-card"><a [routerLink]="[item.mediaType === 'SERIES' ? '/series' : '/catalog', item.tmdbId]">@if (item.posterPath) { <img [src]="posterUrl(item)" [alt]="'Pôster de ' + item.title" /> } @else { <span><i class="ph" [class.ph-film-strip]="item.mediaType === 'MOVIE'" [class.ph-television]="item.mediaType === 'SERIES'"></i></span> }<div><h2>{{ item.title }}</h2><p>{{ item.subtitle || (item.mediaType === 'MOVIE' ? 'Filme' : 'Série') }}</p></div></a><button type="button" [attr.aria-label]="'Remover ' + item.title + ' da coleção'" (click)="removeItem(current, item)"><i class="ph ph-x"></i></button></article> }</section></section> }
        @else { <section class="collection-empty"><i class="ph ph-squares-four"></i><h2>Esta coleção ainda está vazia</h2><p>Volte à Biblioteca para encontrar filmes e séries para esta lista.</p><a class="btn btn-primary" routerLink="/library">Adicionar títulos</a></section> }
      } @else { <section class="collection-empty"><i class="ph ph-folder-notch-open"></i><h2>Coleção não encontrada</h2><p>Ela pode ter sido removida ou não estar disponível neste computador.</p><a class="btn btn-primary" routerLink="/library">Voltar à Biblioteca</a></section> }
    </div>
  `,
})
export class CollectionDetailsPage {
  private readonly libraryApi = inject(LibraryApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly state = signal<'loading' | 'ready'>('loading');
  readonly collection = signal<LibraryCollection | null>(null);
  readonly collectionId = computed(() => Number(this.route.snapshot.paramMap.get('collectionId')));

  constructor() {
    this.route.paramMap.pipe(
      map((params) => Number(params.get('collectionId'))),
      switchMap((id) => this.libraryApi.collections().pipe(map((collections) => ({ id, collections })))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(({ id, collections }) => {
      this.collection.set(collections.find((collection) => collection.id === id) ?? null);
      this.state.set('ready');
    });
  }

  posterUrl(item: FavoriteItem): string { return tmdbImage(item.posterPath, 'poster-card'); }

  removeItem(collection: LibraryCollection, item: FavoriteItem): void {
    this.libraryApi.removeFromCollection(collection.id, item.mediaType, item.tmdbId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.collection.update((current) => current ? { ...current, items: current.items.filter((entry) => entry.mediaType !== item.mediaType || entry.tmdbId !== item.tmdbId) } : current),
    });
  }

  deleteCollection(collection: LibraryCollection): void {
    if (!confirm(`Excluir a coleção “${collection.name}”? Os títulos permanecerão na Biblioteca.`)) return;
    this.libraryApi.removeCollection(collection.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => void this.router.navigate(['/library']) });
  }
}
