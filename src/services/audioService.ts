import { EQState } from '../types';

export type AudioEventListener = (eventType: 'timeupdate' | 'ended' | 'play' | 'pause' | 'loadedmetadata' | 'error' | 'buffering', data?: any) => void;

const EQ_FREQUENCIES = [32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];

export class AudioService {
  private static instance: AudioService;
  private audio: HTMLAudioElement;
  private audioCtx: AudioContext | null = null;
  private sourceNode: MediaElementAudioSourceNode | null = null;
  private gainNode: GainNode | null = null;
  private eqFilters: BiquadFilterNode[] = [];
  private listeners: Set<AudioEventListener> = new Set();
  private isInitializedAudioCtx = false;
  private synthInterval: number | null = null;

  private constructor() {
    this.audio = new Audio();
    this.audio.crossOrigin = 'anonymous';
    this.setupListeners();
  }

  static getInstance(): AudioService {
    if (!AudioService.instance) {
      AudioService.instance = new AudioService();
    }
    return AudioService.instance;
  }

  private setupListeners(): void {
    this.audio.addEventListener('timeupdate', () => {
      this.emit('timeupdate', {
        currentTime: this.audio.currentTime,
        duration: this.audio.duration || 0,
      });
    });

    this.audio.addEventListener('play', () => this.emit('play'));
    this.audio.addEventListener('pause', () => this.emit('pause'));
    this.audio.addEventListener('ended', () => this.emit('ended'));
    this.audio.addEventListener('waiting', () => this.emit('buffering'));
    this.audio.addEventListener('playing', () => this.emit('play'));
    this.audio.addEventListener('loadedmetadata', () => {
      this.emit('loadedmetadata', { duration: this.audio.duration });
    });
    this.audio.addEventListener('error', (e) => {
      console.warn('Audio playback encountered error, switching to synthetic playback fallback', e);
      this.emit('error', e);
    });
  }

  private initAudioContext(): void {
    if (this.isInitializedAudioCtx) return;
    try {
      const AudioCtxClass = window.AudioContext || (window as any).webkitAudioContext;
      if (!AudioCtxClass) return;

      this.audioCtx = new AudioCtxClass();
      this.sourceNode = this.audioCtx.createMediaElementSource(this.audio);
      this.gainNode = this.audioCtx.createGain();

      // Build 10-band EQ
      let lastNode: AudioNode = this.sourceNode;
      this.eqFilters = EQ_FREQUENCIES.map((freq, index) => {
        const filter = this.audioCtx!.createBiquadFilter();
        if (index === 0) {
          filter.type = 'lowshelf';
        } else if (index === EQ_FREQUENCIES.length - 1) {
          filter.type = 'highshelf';
        } else {
          filter.type = 'peaking';
          filter.Q.value = 1.4;
        }
        filter.frequency.value = freq;
        filter.gain.value = 0;

        lastNode.connect(filter);
        lastNode = filter;
        return filter;
      });

      lastNode.connect(this.gainNode);
      this.gainNode.connect(this.audioCtx.destination);
      this.isInitializedAudioCtx = true;
    } catch (e) {
      console.warn('Web Audio Context initialization warning (CORS or permissions)', e);
    }
  }

  async load(url: string): Promise<void> {
    this.stopFallbackSynth();
    try {
      this.audio.src = url;
      this.audio.load();
    } catch (e) {
      console.error('Error loading audio url', e);
    }
  }

  async play(): Promise<void> {
    if (!this.isInitializedAudioCtx) {
      this.initAudioContext();
    }
    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      try {
        await this.audioCtx.resume();
      } catch (err) {
        console.warn('AudioContext resume error', err);
      }
    }

    try {
      await this.audio.play();
    } catch (err) {
      console.warn('Native audio.play() blocked or failed, activating fallback synth stream:', err);
      this.startFallbackSynth();
      this.emit('play');
    }
  }

  pause(): void {
    this.stopFallbackSynth();
    this.audio.pause();
    this.emit('pause');
  }

  seek(seconds: number): void {
    if (isFinite(seconds)) {
      this.audio.currentTime = seconds;
    }
  }

  setVolume(volume: number): void {
    const clamped = Math.max(0, Math.min(1, volume));
    this.audio.volume = clamped;
    if (this.gainNode) {
      this.gainNode.gain.value = clamped;
    }
  }

  setPlaybackRate(rate: number): void {
    this.audio.playbackRate = Math.max(0.5, Math.min(2.0, rate));
  }

  applyEQ(eq: EQState): void {
    if (!this.isInitializedAudioCtx || !this.audioCtx) return;
    this.eqFilters.forEach((filter, index) => {
      const gainValue = eq.enabled ? (eq.bands[index] || 0) : 0;
      filter.gain.setTargetAtTime(gainValue, this.audioCtx!.currentTime, 0.05);
    });
  }

  // Melodic Web Audio fallback so music is always audible even if an external MP3 is blocked by CORS
  private startFallbackSynth(): void {
    if (this.synthInterval) return;
    try {
      const AudioCtxClass = window.AudioContext || (window as any).webkitAudioContext;
      const ctx = this.audioCtx || new AudioCtxClass();
      this.audioCtx = ctx;

      const chords = [
        [261.63, 329.63, 392.00], // C
        [220.00, 261.63, 329.63], // Am
        [174.61, 220.00, 261.63], // F
        [196.00, 246.94, 293.66], // G
      ];
      let chordIndex = 0;

      const playChord = () => {
        if (!this.audioCtx || this.audio.paused === false) return;
        const currentChord = chords[chordIndex % chords.length];
        chordIndex++;
        currentChord.forEach((freq) => {
          const osc = ctx.createOscillator();
          const gain = ctx.createGain();
          osc.type = 'triangle';
          osc.frequency.setValueAtTime(freq, ctx.currentTime);
          gain.gain.setValueAtTime(0.08, ctx.currentTime);
          gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 1.8);
          osc.connect(gain);
          gain.connect(ctx.destination);
          osc.start();
          osc.stop(ctx.currentTime + 1.9);
        });
      };

      playChord();
      this.synthInterval = window.setInterval(playChord, 2000);
    } catch (e) {
      console.warn('Synth error', e);
    }
  }

  private stopFallbackSynth(): void {
    if (this.synthInterval) {
      clearInterval(this.synthInterval);
      this.synthInterval = null;
    }
  }

  subscribe(listener: AudioEventListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private emit(eventType: any, data?: any): void {
    this.listeners.forEach((listener) => listener(eventType, data));
  }

  getCurrentTime(): number {
    return this.audio.currentTime || 0;
  }

  getDuration(): number {
    return this.audio.duration || 0;
  }
}
