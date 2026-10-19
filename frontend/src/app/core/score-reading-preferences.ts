import { effect, inject, Injectable, signal } from '@angular/core';
import { DesktopPreferences } from './desktop-preferences';

export const READING_DEFAULTS = {
  readingEnabled: false,
  readingLanguage: 'letters' as const,
  readingOctave: false,
  readingScope: 'all' as const,
  followFingering: false,
};

@Injectable({ providedIn: 'root' })
export class ScoreReadingPreferences {
  private readonly preferences = inject(DesktopPreferences);
  private readonly initial = { ...READING_DEFAULTS, ...this.preferences.snapshot() };
  readonly readingEnabled = signal(this.initial.readingEnabled);
  readonly readingLanguage = signal<'letters' | 'portuguese'>(this.initial.readingLanguage);
  readonly readingOctave = signal(this.initial.readingOctave);
  readonly readingScope = signal<'all' | 'selection'>(this.initial.readingScope);
  readonly followFingering = signal(this.initial.followFingering);

  constructor() {
    effect(() => {
      this.preferences.update({
        readingEnabled: this.readingEnabled(),
        readingLanguage: this.readingLanguage(),
        readingOctave: this.readingOctave(),
        readingScope: this.readingScope(),
        followFingering: this.followFingering(),
      });
    });
  }

  reset(): void {
    this.readingEnabled.set(READING_DEFAULTS.readingEnabled);
    this.readingLanguage.set(READING_DEFAULTS.readingLanguage);
    this.readingOctave.set(READING_DEFAULTS.readingOctave);
    this.readingScope.set(READING_DEFAULTS.readingScope);
    this.followFingering.set(READING_DEFAULTS.followFingering);
  }
}
