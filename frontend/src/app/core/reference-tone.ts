import { inject, Injectable, InjectionToken, OnDestroy, signal } from '@angular/core';
import { AudioSettings, validReference } from './audio-settings';

export const REFERENCE_AUDIO_FACTORY = new InjectionToken<() => AudioContext>('reference audio', {
  providedIn: 'root',
  factory: () => () => new AudioContext({ latencyHint: 'interactive' }),
});
@Injectable({ providedIn: 'root' })
export class ReferenceTone implements OnDestroy {
  private readonly factory = inject(REFERENCE_AUDIO_FACTORY);
  private readonly settings = inject(AudioSettings);
  readonly playing = this.settings.referencePlaying;
  readonly error = signal('');
  private context?: AudioContext;
  private oscillator?: OscillatorNode;
  private generation = 0;
  private disposed = false;

  async play(hz: number): Promise<void> {
    this.stop();
    if (this.disposed || !validReference(hz)) return;
    await this.playFrequency(hz);
  }
  async playPitch(hz: number): Promise<void> {
    this.stop();
    if (this.disposed || !Number.isFinite(hz) || hz < 20 || hz > 20_000) return;
    await this.playFrequency(hz);
  }
  private async playFrequency(hz: number): Promise<void> {
    const generation = this.generation;
    this.error.set('');
    this.playing.set(true);
    try {
      const context = this.factory();
      this.context = context;
      await context.resume();
      if (this.disposed || generation !== this.generation) return;
      const oscillator = context.createOscillator();
      this.oscillator = oscillator;
      const gain = context.createGain();
      const start = context.currentTime + 0.03;
      oscillator.type = 'sine';
      oscillator.frequency.setValueAtTime(hz, start);
      gain.gain.setValueAtTime(0, start);
      gain.gain.linearRampToValueAtTime(0.06, start + 0.03);
      gain.gain.setValueAtTime(0.06, start + 1.8);
      gain.gain.linearRampToValueAtTime(0, start + 2);
      oscillator.connect(gain);
      gain.connect(context.destination);
      oscillator.onended = () => {
        if (generation === this.generation) this.stop();
      };
      oscillator.start(start);
      oscillator.stop(start + 2);
    } catch {
      if (generation === this.generation) {
        this.stop();
        this.error.set(
          'Não foi possível tocar a referência. Confira a saída e o volume do navegador e tente novamente.',
        );
      }
    }
  }
  stop(): void {
    ++this.generation;
    const oscillator = this.oscillator;
    this.oscillator = undefined;
    if (oscillator) {
      oscillator.onended = null;
      try {
        oscillator.stop();
      } catch {
        /* Already ended. */
      }
      oscillator.disconnect();
    }
    const context = this.context;
    this.context = undefined;
    if (context) void context.close().catch(() => undefined);
    this.playing.set(false);
  }
  ngOnDestroy(): void {
    this.disposed = true;
    this.stop();
  }
}
