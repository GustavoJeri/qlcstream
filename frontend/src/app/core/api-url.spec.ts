import { apiUrl } from './api-url';

describe('Desktop API isolation', () => {
  afterEach(() => vi.unstubAllGlobals());
  function desktop() { vi.stubGlobal('window', {location: {hostname: 'tauri.localhost', port: ''}}); }
  it('keeps the ordinary local API default', () => {
    desktop();
    vi.stubGlobal('QLCSTREAM_DESKTOP_API_ORIGIN', undefined);
    expect(apiUrl('/api/library')).toBe('http://127.0.0.1:8080/api/library');
  });
  it('allows an explicitly built loopback fixture without using the default installation', () => {
    desktop();
    vi.stubGlobal('QLCSTREAM_DESKTOP_API_ORIGIN', 'http://127.0.0.1:18091');
    expect(apiUrl('/api/library')).toBe('http://127.0.0.1:18091/api/library');
  });
  it('refuses remote or credential-bearing overrides', () => {
    desktop();
    for (const origin of ['https://example.com', 'http://user:secret@localhost:8080', 'http://localhost:8080/path']) {
      vi.stubGlobal('QLCSTREAM_DESKTOP_API_ORIGIN', origin);
      expect(() => apiUrl('/api/library')).toThrow();
    }
  });
  it('keeps ordinary web requests on their own origin', () => {
    vi.stubGlobal('window', {location: {hostname: 'localhost', port: '14291'}});
    expect(apiUrl('/api/library')).toBe('/api/library');
  });
});
