import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { apiUrl } from './api-url';

export interface IntegrationStatus {
  id: 'tmdb' | 'prowlarr' | 'qbittorrent';
  configured: boolean;
  status: 'unknown' | 'not_configured' | 'checking' | 'available' | 'authentication_rejected' | 'unavailable';
  reason: string;
  checkedAt: string | null;
  expiresAt: string | null;
  retryAfter: string | null;
}

export interface StorageDiagnostic {
  status: 'blocked' | 'inconclusive';
  backend: string;
  mapping: string;
  sharedDirectoryVerified: boolean;
  checkedAt: string;
}

export type IntegrationId = IntegrationStatus['id'];

export interface IntegrationSettingsState {
  id: IntegrationId;
  source: 'environment' | 'application';
  baseUrl: string;
  usernamePresent: boolean;
  secretPresent: boolean;
  configured: boolean;
}

export interface IntegrationCandidate {
  baseUrl?: string;
  username?: string;
  secret?: { action: 'replace'; value: string } | { action: 'clear' };
}

export type IntegrationSettingsChange =
  | (IntegrationCandidate & { source: 'application' })
  | { source: 'environment' };

export interface IntegrationCandidateResult {
  id: IntegrationId;
  configured: boolean;
  reason: string;
}

@Injectable({ providedIn: 'root' })
export class SystemApiService {
  private readonly http = inject(HttpClient);
  integrationKey(): Observable<{ ready: boolean }> {
    return this.http.get<{ ready: boolean }>(apiUrl('/api/system/integration-settings/key'));
  }
  provisionIntegrationKey(): Observable<{ ready: boolean }> {
    return this.http.post<{ ready: boolean }>(apiUrl('/api/system/integration-settings/key'), {});
  }
  integrationSettings(): Observable<IntegrationSettingsState[]> {
    return this.http.get<IntegrationSettingsState[]>(apiUrl('/api/system/integration-settings'));
  }
  updateIntegration(id: IntegrationId, change: IntegrationSettingsChange): Observable<IntegrationSettingsState> {
    return this.http.put<IntegrationSettingsState>(apiUrl(`/api/system/integration-settings/${id}`), change);
  }
  testIntegration(id: IntegrationId, candidate: IntegrationCandidate): Observable<IntegrationCandidateResult> {
    return this.http.post<IntegrationCandidateResult>(apiUrl(`/api/system/integration-settings/${id}/test`), candidate);
  }
  checkStorage(): Observable<StorageDiagnostic> { return this.http.post<StorageDiagnostic>(apiUrl('/api/system/storage/check'), {}); }
  integrations(): Observable<IntegrationStatus[]> { return this.http.get<IntegrationStatus[]>(apiUrl('/api/system/integrations')); }
  checkIntegrations(): Observable<IntegrationStatus[]> { return this.http.post<IntegrationStatus[]>(apiUrl('/api/system/integrations/check'), {}); }
}
