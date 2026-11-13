import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IntegrationAvailabilityService } from './integration-availability.service';

describe('Availability during partial setup', () => {
  let http: HttpTestingController;
  let service: IntegrationAvailabilityService;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController); service = TestBed.inject(IntegrationAvailabilityService);
  });
  afterEach(() => http.verify());
  it('keeps catalog usable while folder failure blocks downloads', () => {
    service.refresh();
    http.expectOne('/api/system/integration-settings').flush(['tmdb', 'prowlarr', 'qbittorrent'].map(id => ({ id, configured: true })));
    http.expectOne('/api/system/storage/check').flush({ backend: 'missing_directory', mapping: 'compatible_unproven' });
    expect(service.ready('tmdb')).toBe(true); expect(service.ready('qbittorrent')).toBe(false);
    service.refresh();
    http.expectOne('/api/system/integration-settings').flush(['tmdb', 'prowlarr', 'qbittorrent'].map(id => ({ id, configured: true })));
    http.expectOne('/api/system/storage/check').flush({ backend: 'read_write_verified', mapping: 'compatible_unproven' });
    expect(service.ready('qbittorrent')).toBe(true);
  });
  it('does not let an older response overwrite the current configuration', () => {
    service.refresh();
    const previous = http.expectOne('/api/system/integration-settings');
    const previousStorage = http.expectOne('/api/system/storage/check');
    service.refresh();
    http.expectOne('/api/system/integration-settings').flush([{ id: 'tmdb', configured: true }]);
    http.expectOne('/api/system/storage/check').flush({ backend: 'read_write_verified', mapping: 'compatible_unproven' });
    previous.flush([]); previousStorage.flush({ backend: 'missing_directory' });
    expect(service.ready('tmdb')).toBe(true); expect(service.storageAttention()).toBe(false);
  });
});
