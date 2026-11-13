export type TmdbImageProfile = 'poster-card' | 'poster-detail' | 'backdrop-hero' | 'cast' | 'episode' | 'thumbnail';
const sizes: Record<TmdbImageProfile, string> = { 'poster-card': 'w342', 'poster-detail': 'w500', 'backdrop-hero': 'w1280', cast: 'w185', episode: 'w300', thumbnail: 'w185' };
export function tmdbImage(path: string | null | undefined, profile: TmdbImageProfile): string { return path ? `https://image.tmdb.org/t/p/${sizes[profile]}${path}` : ''; }
export function tmdbPosterSrcset(path: string | null | undefined): string { return path ? `https://image.tmdb.org/t/p/w185${path} 185w, https://image.tmdb.org/t/p/w342${path} 342w, https://image.tmdb.org/t/p/w500${path} 500w` : ''; }
