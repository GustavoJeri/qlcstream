import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { IntegrationSettingsState, SystemApiService } from './system-api.service';

describe('SystemApiService integration settings', () => {
  let service: SystemApiService;
  let http: HttpTestingController;
  const state: IntegrationSettingsState = {
    id: 'tmdb', source: 'environment', baseUrl: 'https://api.themoviedb.org/3',
    usernamePresent: false, secretPresent: true, configured: true,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(SystemApiService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('loads the server state without issuing updates', () => {
    service.integrationSettings().subscribe((result) => expect(result).toEqual([state]));
    const request = http.expectOne('/api/system/integration-settings');
    expect(request.request.method).toBe('GET');
    request.flush([state]);
  });

  it('tests a candidate through the non-persistent endpoint', () => {
    const candidate = { secret: { action: 'replace' as const, value: 'fictional_candidate' } };
    service.testIntegration('tmdb', candidate).subscribe((result) => expect(result.reason).toBe('authentication_rejected'));
    const request = http.expectOne('/api/system/integration-settings/tmdb/test');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(candidate);
    expect(request.request.urlWithParams).not.toContain('fictional_candidate');
    request.flush({ id: 'tmdb', configured: true, reason: 'authentication_rejected' });
  });

  it('preserves omission of the secret when updating other fields', () => {
    service.updateIntegration('tmdb', { source: 'application', baseUrl: state.baseUrl }).subscribe();
    const request = http.expectOne('/api/system/integration-settings/tmdb');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ source: 'application', baseUrl: state.baseUrl });
    expect(Object.hasOwn(request.request.body, 'secret')).toBe(false);
    request.flush({ ...state, source: 'application' });
  });

  it('sends clearing and environment recovery only as explicit actions', () => {
    service.updateIntegration('tmdb', { source: 'application', secret: { action: 'clear' } }).subscribe();
    const clear = http.expectOne('/api/system/integration-settings/tmdb');
    expect(clear.request.body).toEqual({ source: 'application', secret: { action: 'clear' } });
    clear.flush({ ...state, source: 'application', secretPresent: false, configured: false });
    service.updateIntegration('tmdb', { source: 'environment' }).subscribe();
    const restore = http.expectOne('/api/system/integration-settings/tmdb');
    expect(restore.request.body).toEqual({ source: 'environment' });
    restore.flush(state);
  });
});
