import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { apiUrl } from './api-url';

export interface DownloadSummary {
  id: string;
  movieTmdbId: number;
  movieTitle: string;
  posterPath: string | null;
  mediaType: 'MOVIE' | 'SERIES';
  releaseTitle: string;
  resolutionHeight: number | null;
  sourceType: string | null;
  dynamicRange: string | null;
  status: string;
  engineState: string | null;
  canRetry: boolean;
  progress: number;
  totalBytes: number | null;
  downloadedBytes: number;
  downloadSpeedBps: number;
  etaSeconds: number | null;
  createdAt: string;
}

export interface DownloadQueue {
  items: DownloadSummary[];
  sync: {
    state: 'waiting' | 'available' | 'unavailable';
    lastSuccessfulAt: string | null;
    queueingEnabled: boolean;
  };
}

export interface CreateDownloadRequest {
  movieTmdbId: number;
  releaseTitle: string;
  acquisitionRef: string;
  indexerName: string | null;
  resolutionHeight: number | null;
  sourceType: string | null;
  dynamicRange: string | null;
}

export interface CreateSeriesDownloadRequest { seriesTmdbId: number; seriesTitle: string; posterPath: string | null; seasonNumber: number; episodeNumber: number | null; releaseTitle: string; acquisitionRef: string; indexerName: string | null; resolutionHeight: number | null; sourceType: string | null; dynamicRange: string | null; }

export interface SubmittedDownload {
  id: string;
  status: string;
}

@Injectable({ providedIn: 'root' })
export class DownloadsApiService {
  private readonly http = inject(HttpClient);

  active(): Observable<DownloadSummary[]> {
    return this.http.get<DownloadSummary[]>(apiUrl('/api/downloads'));
  }

  queue(): Observable<DownloadQueue> {
    return this.http.get<DownloadQueue>(apiUrl('/api/downloads/queue'));
  }

  enqueue(request: CreateDownloadRequest): Observable<SubmittedDownload> {
    return this.http.post<SubmittedDownload>(apiUrl('/api/downloads'), request);
  }

  enqueueSeries(request: CreateSeriesDownloadRequest): Observable<SubmittedDownload> { return this.http.post<SubmittedDownload>(apiUrl('/api/downloads/series'), request); }

  control(id: string, action: 'pause' | 'resume' | 'retry' | 'priority_up' | 'priority_down' | 'cancel'): Observable<void> {
    return this.http.post<void>(apiUrl(`/api/downloads/${id}/${action.toUpperCase()}`), {});
  }
}
