import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { DesktopFileService } from '../core/desktop-file.service';
import { LibraryItem, SeriesLibraryItem } from '../core/library-api.service';
import { LibraryPage } from './library.page';

describe('Library file actions', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] }));
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); vi.unstubAllGlobals(); });
  const item = { id: 1, movieTmdbId: 1, movieTitle: 'Owned', relativePath: 'incoming/ação com espaços.mp4',
    posterPath: null, sizeBytes: 10, resolutionHeight: null, sourceType: null, dynamicRange: null } as LibraryItem;
  function page() {
    const fixture = TestBed.createComponent(LibraryPage);
    for (const request of TestBed.inject(HttpTestingController).match(r => r.url.startsWith('/api/library'))) request.flush([]);
    fixture.componentInstance.items.set([item]);
    return fixture;
  }
  it('renders the native missing-file message without replacing it with a connection error', async () => {
    const fixture = page(); fixture.componentInstance.desktop.set(true);
    vi.spyOn(TestBed.inject(DesktopFileService), 'openInVlc').mockRejectedValue('O arquivo não está mais disponível no computador.');
    fixture.componentInstance.openInVlc(item);
    await Promise.resolve(); await Promise.resolve(); fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('ready');
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('não está mais disponível');
  });
  it('keeps the row and displays the completed stages when deletion fails partially', () => {
    const fixture = page(); fixture.componentInstance.desktop.set(false);
    vi.stubGlobal('confirm', vi.fn(() => true));
    fixture.componentInstance.remove(item);
    const detail = 'O arquivo foi removido, mas a biblioteca não pôde ser atualizada. Repita a exclusão para concluir o registro.';
    TestBed.inject(HttpTestingController).expectOne('/api/library/1').flush({detail}, {status: 503, statusText: 'Unavailable'});
    fixture.detectChanges();
    expect(fixture.componentInstance.items()).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(detail);
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('outros arquivos do torrent serão mantidos'));
  });
  it('offers per-file series deletion and usable location instructions on the web', () => {
    const fixture = page(); fixture.componentInstance.desktop.set(false);
    const episode = { ...item, id: 2, seriesTmdbId: 22, seriesTitle: 'Owned series', seasonNumber: 1,
      episodeNumber: 1 } as unknown as SeriesLibraryItem;
    fixture.componentInstance.seriesFiles.set([episode, {...episode, id: 3, episodeNumber: 2}]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('pasta de mídia escolhida na instalação');
    expect(fixture.nativeElement.textContent).not.toContain('Abrir no VLC');
    vi.stubGlobal('confirm', () => true);
    fixture.componentInstance.remove(episode);
    TestBed.inject(HttpTestingController).expectOne('/api/library/2').flush(null);
    expect(fixture.componentInstance.seriesFiles().map(file => file.id)).toEqual([3]);
    expect(fixture.componentInstance.items().map(file => file.id)).toEqual([1]);
  });
  it('requires explicit confirmation before deleting and disables concurrent file deletions', () => {
    const fixture = page(); fixture.componentInstance.desktop.set(false);
    const second = { ...item, id: 4, movieTitle: 'Second' };
    fixture.componentInstance.items.set([item, second]);
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll('button.delete-file') as NodeListOf<HTMLButtonElement>;
    expect(buttons[0].getAttribute('aria-label')).toBe('Excluir arquivo local de Owned');

    vi.stubGlobal('confirm', vi.fn(() => false));
    fixture.componentInstance.remove(item);
    TestBed.inject(HttpTestingController).expectNone('/api/library/1');
    expect(fixture.componentInstance.items()).toHaveLength(2);

    vi.stubGlobal('confirm', vi.fn(() => true));
    fixture.componentInstance.remove(item);
    fixture.detectChanges();
    expect(buttons[0].disabled).toBe(true);
    expect(buttons[1].disabled).toBe(true);
    fixture.componentInstance.remove(second);
    TestBed.inject(HttpTestingController).expectNone('/api/library/4');
    TestBed.inject(HttpTestingController).expectOne('/api/library/1').flush(null);
    fixture.detectChanges();
    expect(fixture.componentInstance.items().map(entry => entry.id)).toEqual([4]);
  });
  it('prevents overlapping desktop confirmation dialogs while one is pending', async () => {
    const fixture = page(); fixture.componentInstance.desktop.set(true);
    let resolveDialog!: (confirmed: boolean) => void;
    const confirmSpy = vi.spyOn(TestBed.inject(DesktopFileService), 'confirmDeletion')
      .mockImplementation(() => new Promise<boolean>((resolve) => { resolveDialog = resolve; }));
    fixture.componentInstance.remove(item);
    fixture.detectChanges();
    expect(fixture.componentInstance.confirmingDeletion()).toBe(true);
    fixture.componentInstance.remove(item);
    expect(confirmSpy).toHaveBeenCalledTimes(1);

    resolveDialog(false);
    await Promise.resolve(); await Promise.resolve();
    expect(fixture.componentInstance.confirmingDeletion()).toBe(false);
    TestBed.inject(HttpTestingController).expectNone('/api/library/1');
  });
});
