declare const QLCSTREAM_DESKTOP_API_ORIGIN: string | undefined;

function desktopApiOrigin(): string {
  const configured = typeof QLCSTREAM_DESKTOP_API_ORIGIN === 'undefined'
    ? 'http://127.0.0.1:8080' : QLCSTREAM_DESKTOP_API_ORIGIN;
  const url = new URL(configured);
  if (!['http:', 'https:'].includes(url.protocol)
      || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('O endereço desktop deve apontar para a API local, sem credenciais ou subcaminho.');
  }
  return url.origin;
}

export function isDesktopApp(): boolean {
  if (typeof window === 'undefined') return false;
  return '__TAURI_INTERNALS__' in window
    || window.location.hostname === 'tauri.localhost'
    || window.location.port === '4201';
}

export function apiUrl(path: string): string {
  return isDesktopApp() ? `${desktopApiOrigin()}${path}` : path;
}
