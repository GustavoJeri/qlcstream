import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { apiUrl } from './api-url';

export interface IntegrationStatus { id: 'tmdb' | 'prowlarr' | 'qbittorrent'; configured: boolean; }

export interface StorageDiagnostic {
  status: 'blocked' | 'inconclusive';
  backend: string;
  mapping: string;
  sharedDirectoryVerified: boolean;
  checkedAt: string;
}

@Injectable({ providedIn: 'root' })
export class SystemApiService {
  private readonly http = inject(HttpClient);
  checkStorage(): Observable<StorageDiagnostic> { return this.http.post<StorageDiagnostic>(apiUrl('/api/system/storage/check'), {}); }
  integrations(): Observable<IntegrationStatus[]> { return this.http.get<IntegrationStatus[]>(apiUrl('/api/system/integrations')); }
}
