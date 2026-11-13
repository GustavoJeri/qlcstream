import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { timeout } from 'rxjs';
import { IntegrationId, IntegrationSettingsState, SystemApiService } from './system-api.service';

@Injectable({ providedIn: 'root' })
export class IntegrationAvailabilityService {
  private readonly api = inject(SystemApiService);
  private readonly destroy = inject(DestroyRef);
  private generation = 0;
  readonly rows = signal<IntegrationSettingsState[]>([]);
  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly storageAttention = signal(true);
  ready(id: IntegrationId): boolean { return this.state() === 'ready' && !!this.rows().find(row => row.id === id)?.configured
    && (id !== 'qbittorrent' || !this.storageAttention()); }
  refresh(): void {
    const generation = ++this.generation;
    this.state.set('loading'); this.storageAttention.set(true);
    this.api.integrationSettings().pipe(timeout(6000), takeUntilDestroyed(this.destroy)).subscribe({
      next: rows => { if (generation === this.generation) { this.rows.set(rows); this.state.set('ready'); } },
      error: () => { if (generation === this.generation) this.state.set('error'); },
    });
    this.api.checkStorage().pipe(timeout(8000), takeUntilDestroyed(this.destroy)).subscribe({
      next: result => { if (generation === this.generation) this.storageAttention.set(result.backend !== 'read_write_verified' || result.mapping !== 'compatible_unproven'); },
      error: () => { if (generation === this.generation) this.storageAttention.set(true); },
    });
  }
}
