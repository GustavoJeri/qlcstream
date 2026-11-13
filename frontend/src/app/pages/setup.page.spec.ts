import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SetupPage } from './setup.page';

describe('First-run setup', () => {
  let http: HttpTestingController;
  const rows = ['tmdb', 'prowlarr', 'qbittorrent'].map(id => ({ id, source: 'environment', baseUrl: '', secretPresent: false, usernamePresent: false, configured: false }));
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function open(configured = false, key = true) {
    const fixture = TestBed.createComponent(SetupPage);
    http.expectOne('/api/system/integration-settings').flush(configured ? rows.map(row => ({ ...row, configured: true, secretPresent: true })) : rows);
    http.expectOne('/api/system/integration-settings/key').flush({ ready: key });
    http.expectOne('/api/library/storage').flush({ downloadDirectory: 'incoming' });
    fixture.detectChanges();
    return fixture;
  }
  it('offers explicit key creation without provisioning or saving automatically', () => {
    const fixture = open(false, false);
    expect(fixture.componentInstance.step()).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('Criar chave de proteção');
    fixture.componentInstance.provision();
    http.expectOne('/api/system/integration-settings/key').flush({ ready: true });
    expect(fixture.componentInstance.keyReady()).toBe(true);
  });
  it('keeps existing configuration and secrets untouched on reopening', () => {
    const fixture = open(true);
    expect(fixture.componentInstance.step()).toBe(3);
    fixture.componentInstance.select(0);
    expect(fixture.componentInstance.secret).toBe('');
    expect(fixture.componentInstance.catalogReady()).toBe(true);
  });
  it('tests a candidate without persistence and explains authentication failure', () => {
    const fixture = open(); const page = fixture.componentInstance;
    page.secret = 'fictional_candidate'; page.test();
    const request = http.expectOne('/api/system/integration-settings/tmdb/test');
    expect(request.request.body.secret).toEqual({ action: 'replace', value: 'fictional_candidate' });
    page.select(1); expect(page.step()).toBe(0);
    request.flush({ id: 'tmdb', configured: true, reason: 'authentication_rejected' });
    expect(page.message()).toContain('Credencial recusada');
    expect(page.secret).toBe('fictional_candidate');
  });
  it('requires explicit adoption and preserves input after a failed save', () => {
    const fixture = open(); const page = fixture.componentInstance;
    page.secret = 'fictional_candidate'; page.save();
    http.expectNone('/api/system/integration-settings/tmdb');
    page.adopt = true; page.save();
    http.expectOne('/api/system/integration-settings/tmdb').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(page.secret).toBe('fictional_candidate');
    expect(page.step()).toBe(0);
    expect(page.message()).toContain('campos foram preservados');
  });
  it('omits saved secrets, advances only after saving and resumes the next missing integration', () => {
    const fixture = open(); const page = fixture.componentInstance;
    page.adopt = true; page.save();
    const request = http.expectOne('/api/system/integration-settings/tmdb');
    expect(Object.hasOwn(request.request.body, 'secret')).toBe(false);
    request.flush({ ...rows[0], configured: true, secretPresent: true, source: 'application' });
    expect(page.step()).toBe(1); expect(page.secret).toBe(''); expect(page.catalogReady()).toBe(true);
    page.load();
    http.expectOne('/api/system/integration-settings').flush([{ ...rows[0], configured: true }, rows[1], rows[2]]);
    http.expectOne('/api/system/integration-settings/key').flush({ ready: true });
    http.expectOne('/api/library/storage').flush({ downloadDirectory: 'incoming' });
    expect(page.step()).toBe(1);
    page.select(3); expect(page.catalogReady()).toBe(true);
  });
  it('shows folder errors and keeps physical mapping inconclusive after recovery', () => {
    const fixture = open(); const page = fixture.componentInstance;
    page.select(3); page.directory = 'missing'; page.saveFolder();
    http.expectOne('/api/library/storage').flush({ downloadDirectory: 'missing' });
    http.expectOne('/api/system/storage/check').flush({ backend: 'missing_directory', mapping: 'compatible_unproven', sharedDirectoryVerified: false, status: 'blocked', checkedAt: '' });
    expect(page.folderHelp(page.storage()!)).toContain('não passou');
    page.directory = 'incoming'; page.saveFolder();
    http.expectOne('/api/library/storage').flush({ downloadDirectory: 'incoming' });
    http.expectOne('/api/system/storage/check').flush({ backend: 'read_write_verified', mapping: 'compatible_unproven', sharedDirectoryVerified: false, status: 'inconclusive', checkedAt: '' });
    expect(page.folderHelp(page.storage()!)).toContain('inconclusivo');
  });
});
