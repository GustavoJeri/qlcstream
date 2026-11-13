import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';

import { DownloadQueue, DownloadsApiService, DownloadSummary } from '../core/downloads-api.service';
import { DownloadsPage } from './downloads.page';

describe('Downloads queue', () => {
  const lastSuccessfulAt = '2026-11-13T12:00:00Z';
  const item: DownloadSummary = {
    id: '1', movieTmdbId: 1, movieTitle: 'Filme de teste', posterPath: null, mediaType: 'MOVIE',
    releaseTitle: 'Teste', resolutionHeight: 1080, sourceType: null, dynamicRange: null,
    status: 'ERROR', engineState: 'missingFiles', canRetry: true, progress: 0.4, totalBytes: 1000,
    downloadedBytes: 400, downloadSpeedBps: 0, etaSeconds: null, createdAt: lastSuccessfulAt,
  };

  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('EventSource', class {
      addEventListener() {}
      close() {}
    });
    vi.stubGlobal('confirm', vi.fn(() => true));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('keeps the last transfer when synchronization fails and restores actions after recovery', async () => {
    const responses: Subject<DownloadQueue>[] = [];
    const control = vi.fn(() => new Subject<void>());
    TestBed.configureTestingModule({
      providers: [provideRouter([]), {
        provide: DownloadsApiService,
        useValue: { queue: () => {
          const response = new Subject<DownloadQueue>();
          responses.push(response);
          return response;
        }, control },
      }],
    });
    const fixture = TestBed.createComponent(DownloadsPage);
    const page = fixture.componentInstance;
    const other = { ...item, id: '2' };
    const items = [item, other];
    const sync = { state: 'available' as const, lastSuccessfulAt, queueingEnabled: true };

    await vi.advanceTimersByTimeAsync(0);
    responses[0].next({ items, sync });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Arquivo ausente');
    expect(page.canPrioritize('PAUSED')).toBe(true);

    page.load();
    responses[1].next({ items, sync: { ...sync, state: 'unavailable', queueingEnabled: false } });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Dados desatualizados');
    expect(fixture.nativeElement.textContent).toContain('40%');
    expect(page.lastSuccessfulAt()).toBe(lastSuccessfulAt);
    expect(page.canPrioritize('PAUSED')).toBe(false);
    page.control(item, 'retry');
    expect(control).not.toHaveBeenCalled();

    page.load();
    responses[2].next({ items, sync });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Dados desatualizados');
    page.control(item, 'retry');
    page.control(item, 'retry');
    page.control(other, 'cancel');
    expect(control.mock.calls).toEqual([['1', 'retry'], ['2', 'cancel']]);
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('serão mantidos'));

    page.load();
    responses[3].error(new Error('offline'));
    expect(page.downloads()).toEqual(items);
    expect(page.stale()).toBe(true);
  });
});
