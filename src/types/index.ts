export type MusicSource = 'youtube' | 'spotify' | 'local';

export interface Artist {
  id: string;
  name: string;
  thumbnailUrl: string;
  subscribers?: string;
  bio?: string;
  monthlyListeners?: string;
  genres?: string[];
}

export interface Album {
  id: string;
  title: string;
  artist: string;
  artistId?: string;
  thumbnailUrl: string;
  year?: string;
  tracksCount?: number;
  source?: MusicSource;
}

export interface LyricsLine {
  time: number; // in seconds
  text: string;
  words?: { word: string; time: number }[];
  translation?: string;
}

export interface Song {
  id: string;
  title: string;
  artists: { id: string; name: string }[];
  album?: { id: string; title: string; thumbnailUrl?: string };
  duration: number; // in seconds
  thumbnailUrl: string;
  source: MusicSource;
  audioUrl: string;
  youtubeVideoId?: string;
  spotifyId?: string;
  isrc?: string;
  explicit?: boolean;
  isLiked?: boolean;
  playCount?: number;
  losslessAvailable?: boolean;
  losslessQuality?: 'FLAC 24-bit/96kHz' | 'FLAC 16-bit/44.1kHz' | 'AAC 320kbps';
  lyrics?: LyricsLine[];
}

export interface Playlist {
  id: string;
  title: string;
  description?: string;
  thumbnailUrl: string;
  tracks: Song[];
  isLocal?: boolean;
  isPinned?: boolean;
  source: MusicSource;
  createdAt: number;
}

export interface SpeedDialItem {
  id: string;
  title: string;
  subtitle: string;
  thumbnailUrl: string;
  type: 'song' | 'playlist' | 'album' | 'artist';
  targetId: string;
}

export interface PlaybackHistoryItem {
  song: Song;
  playedAt: number;
}

export interface AppSettings {
  // Appearance
  themeMode: 'dark' | 'amoled' | 'light';
  accentColor: 'indigo' | 'spotify' | 'emerald' | 'amber' | 'rose' | 'cyan';
  useDynamicTheme: boolean;
  
  // Spotify Integration
  spotifyConnected: boolean;
  spotifyUsername?: string;
  useSpotifyForHome: boolean;
  useSpotifyForSearch: boolean;
  spotifyOnlyMode: boolean;
  
  // Lossless Audio (Qobuz)
  enableLossless: boolean;
  losslessTier: 'FLAC 24-bit/96kHz' | 'FLAC 16-bit/44.1kHz' | 'AAC 320kbps';
  losslessResolver: 'Monokenny' | 'Jumo' | 'Squid';

  // Audio & Player
  audioQuality: 'auto' | 'high' | 'normal';
  normalizeAudio: boolean;
  skipSilence: boolean;
  sponsorBlockEnabled: boolean;
  defaultLyricsProvider: 'BetterLyrics' | 'LrcLib' | 'YouTube';

  // Stats & Misc
  discordRpc: boolean;
  lastFmScrobbling: boolean;
}

export type RepeatMode = 'off' | 'all' | 'one';

export interface EQState {
  enabled: boolean;
  preset: string;
  bands: number[]; // 10 bands gains in dB (-12 to +12)
  bassBoost: number; // 0 to 100
  virtualizer: number; // 0 to 100
}
