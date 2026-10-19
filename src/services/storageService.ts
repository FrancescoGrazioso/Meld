import { AppSettings, Playlist, Song, SpeedDialItem, PlaybackHistoryItem, EQState } from '../types';

const STORAGE_KEYS = {
  SETTINGS: 'meld_settings',
  LIKED_SONGS: 'meld_liked_songs',
  PLAYLISTS: 'meld_playlists',
  HISTORY: 'meld_history',
  SPEED_DIAL: 'meld_speed_dial',
  MATCH_OVERRIDES: 'meld_match_overrides',
  EQ_STATE: 'meld_eq_state',
  STATS: 'meld_listening_stats',
};

export const defaultSettings: AppSettings = {
  themeMode: 'dark',
  accentColor: 'indigo',
  useDynamicTheme: true,
  spotifyConnected: true,
  spotifyUsername: 'Melder',
  useSpotifyForHome: true,
  useSpotifyForSearch: true,
  spotifyOnlyMode: false,
  enableLossless: true,
  losslessTier: 'FLAC 24-bit/96kHz',
  losslessResolver: 'Monokenny',
  audioQuality: 'high',
  normalizeAudio: true,
  skipSilence: false,
  sponsorBlockEnabled: true,
  defaultLyricsProvider: 'BetterLyrics',
  discordRpc: false,
  lastFmScrobbling: false,
};

export const defaultEQ: EQState = {
  enabled: false,
  preset: 'Flat',
  bands: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  bassBoost: 0,
  virtualizer: 0,
};

export class StorageService {
  static getSettings(): AppSettings {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.SETTINGS);
      return data ? { ...defaultSettings, ...JSON.parse(data) } : defaultSettings;
    } catch {
      return defaultSettings;
    }
  }

  static saveSettings(settings: AppSettings): void {
    try {
      localStorage.setItem(STORAGE_KEYS.SETTINGS, JSON.stringify(settings));
    } catch (e) {
      console.error('Failed to save settings', e);
    }
  }

  static getLikedSongs(): Song[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.LIKED_SONGS);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  static saveLikedSongs(songs: Song[]): void {
    try {
      localStorage.setItem(STORAGE_KEYS.LIKED_SONGS, JSON.stringify(songs));
    } catch (e) {
      console.error('Failed to save liked songs', e);
    }
  }

  static getPlaylists(): Playlist[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.PLAYLISTS);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  static savePlaylists(playlists: Playlist[]): void {
    try {
      localStorage.setItem(STORAGE_KEYS.PLAYLISTS, JSON.stringify(playlists));
    } catch (e) {
      console.error('Failed to save playlists', e);
    }
  }

  static getHistory(): PlaybackHistoryItem[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.HISTORY);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  static addHistory(song: Song): void {
    try {
      const history = StorageService.getHistory();
      const updated = [
        { song, playedAt: Date.now() },
        ...history.filter((h) => h.song.id !== song.id),
      ].slice(0, 100);
      localStorage.setItem(STORAGE_KEYS.HISTORY, JSON.stringify(updated));

      // Also record stats
      const stats = StorageService.getStats();
      stats.totalPlays = (stats.totalPlays || 0) + 1;
      stats.totalDuration = (stats.totalDuration || 0) + Math.min(song.duration || 180, 400);
      stats.playsBySong = stats.playsBySong || {};
      stats.playsBySong[song.title] = (stats.playsBySong[song.title] || 0) + 1;
      stats.playsByArtist = stats.playsByArtist || {};
      const artistName = song.artists[0]?.name || 'Unknown';
      stats.playsByArtist[artistName] = (stats.playsByArtist[artistName] || 0) + 1;
      localStorage.setItem(STORAGE_KEYS.STATS, JSON.stringify(stats));
    } catch (e) {
      console.error('Failed to add to history', e);
    }
  }

  static clearHistory(): void {
    try {
      localStorage.removeItem(STORAGE_KEYS.HISTORY);
    } catch (e) {
      console.error('Failed to clear history', e);
    }
  }

  static getStats(): {
    totalPlays: number;
    totalDuration: number;
    playsBySong: Record<string, number>;
    playsByArtist: Record<string, number>;
  } {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.STATS);
      return data ? JSON.parse(data) : { totalPlays: 42, totalDuration: 7560, playsBySong: {}, playsByArtist: {} };
    } catch {
      return { totalPlays: 42, totalDuration: 7560, playsBySong: {}, playsByArtist: {} };
    }
  }

  static getSpeedDial(): SpeedDialItem[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.SPEED_DIAL);
      return data ? JSON.parse(data) : [];
    } catch {
      return [];
    }
  }

  static saveSpeedDial(items: SpeedDialItem[]): void {
    try {
      localStorage.setItem(STORAGE_KEYS.SPEED_DIAL, JSON.stringify(items));
    } catch (e) {
      console.error('Failed to save speed dial', e);
    }
  }

  static getMatchOverrides(): Record<string, string> {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.MATCH_OVERRIDES);
      return data ? JSON.parse(data) : {};
    } catch {
      return {};
    }
  }

  static setMatchOverride(spotifyId: string, youtubeVideoId: string): void {
    try {
      const overrides = StorageService.getMatchOverrides();
      overrides[spotifyId] = youtubeVideoId;
      localStorage.setItem(STORAGE_KEYS.MATCH_OVERRIDES, JSON.stringify(overrides));
    } catch (e) {
      console.error('Failed to save match override', e);
    }
  }

  static getEQ(): EQState {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.EQ_STATE);
      return data ? { ...defaultEQ, ...JSON.parse(data) } : defaultEQ;
    } catch {
      return defaultEQ;
    }
  }

  static saveEQ(eq: EQState): void {
    try {
      localStorage.setItem(STORAGE_KEYS.EQ_STATE, JSON.stringify(eq));
    } catch (e) {
      console.error('Failed to save EQ state', e);
    }
  }

  static exportBackup(): string {
    return JSON.stringify({
      version: '0.8.9',
      timestamp: Date.now(),
      settings: StorageService.getSettings(),
      likedSongs: StorageService.getLikedSongs(),
      playlists: StorageService.getPlaylists(),
      history: StorageService.getHistory(),
      speedDial: StorageService.getSpeedDial(),
      matchOverrides: StorageService.getMatchOverrides(),
      eq: StorageService.getEQ(),
    }, null, 2);
  }

  static importBackup(jsonString: string): boolean {
    try {
      const data = JSON.parse(jsonString);
      if (data.settings) StorageService.saveSettings(data.settings);
      if (data.likedSongs) StorageService.saveLikedSongs(data.likedSongs);
      if (data.playlists) StorageService.savePlaylists(data.playlists);
      if (data.speedDial) StorageService.saveSpeedDial(data.speedDial);
      if (data.eq) StorageService.saveEQ(data.eq);
      return true;
    } catch (e) {
      console.error('Import failed', e);
      return false;
    }
  }
}
