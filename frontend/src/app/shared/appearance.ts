import { DOCUMENT } from '@angular/common';
import { inject, Injectable, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

@Injectable({ providedIn: 'root' })
export class Appearance {
  private readonly document = inject(DOCUMENT);
  readonly theme = signal<Theme>('dark');

  constructor() {
    try {
      const saved = this.document.defaultView?.localStorage.getItem('arpeon.theme.v1');
      if (saved === 'light') this.theme.set(saved);
    } catch {
      // Appearance remains available when local storage is blocked.
    }
    this.apply();
  }

  setTheme(theme: Theme): void {
    this.theme.set(theme);
    this.apply();
    try {
      this.document.defaultView?.localStorage.setItem('arpeon.theme.v1', theme);
    } catch {
      // Saving a preference must not interrupt the session.
    }
  }

  private apply(): void {
    this.document.documentElement.dataset['theme'] = this.theme();
  }
}
