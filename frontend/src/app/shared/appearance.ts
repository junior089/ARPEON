import { DOCUMENT } from '@angular/common';
import { inject, Injectable, signal } from '@angular/core';
import { DesktopPreferences } from '../core/desktop-preferences';

export type Theme = 'dark' | 'light';

@Injectable({ providedIn: 'root' })
export class Appearance {
  private readonly document = inject(DOCUMENT);
  private readonly preferences = inject(DesktopPreferences);
  readonly theme = signal<Theme>(this.preferences.snapshot().theme);

  constructor() {
    this.apply();
  }

  setTheme(theme: Theme): void {
    this.theme.set(theme);
    this.apply();
    this.preferences.update({ theme });
  }

  private apply(): void {
    this.document.documentElement.dataset['theme'] = this.theme();
  }
}
