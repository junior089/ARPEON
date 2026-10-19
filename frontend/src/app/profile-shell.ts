import { Component, HostListener, inject } from '@angular/core';
import { App } from './app';
import { StudyProfiles } from './core/study-profiles';
import { ProfileSelector } from './features/profiles/profile-selector';
import { LOCAL_FETCH, SESSION_TOKEN } from './core/local-session';

@Component({
  selector: 'app-root',
  imports: [App, ProfileSelector],
  template: `@if (profiles.needsSelection()) {
      @defer (on immediate) {
        <app-profile-selector (returned)="profiles.choosing.set(false)" />
      } @placeholder {
        <p role="status">Preparando perfis…</p>
      }
    } @else if (profiles.account() || profiles.legacy()) {
      @defer (on immediate) {
        <app-workstation (profileRequested)="profiles.choosing.set(true)" />
      } @placeholder {
        <p role="status">Preparando a área de trabalho…</p>
      }
    } @else {
      <main class="profile-start-error">
        <h1>Conta local indisponível</h1>
        <p role="alert">{{ profiles.error() }}</p>
        <button type="button" (click)="reload()">Tentar novamente</button>
        <p>Seus dados não foram removidos.</p>
      </main>
    }`,
  styles: `
    .profile-start-error {
      padding: var(--space-6);
      max-width: 70ch;
      margin: auto;
    }
  `,
})
export class ProfileShell {
  readonly profiles = inject(StudyProfiles);
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  reload(): void {
    location.reload();
  }
  @HostListener('window:arpeon-close') async closeSelector(): Promise<void> {
    if (!this.profiles.needsSelection() && (this.profiles.account() || this.profiles.legacy()))
      return;
    try {
      const response = await this.fetch('/api/shutdown', {
        method: 'POST',
        headers: { 'X-Arpeon-Session': this.token, 'Content-Type': 'application/json' },
        body: '{}',
        credentials: 'omit',
        signal: AbortSignal.timeout(5000),
      });
      if (!response.ok) throw new Error('Não foi possível encerrar o aplicativo.');
    } catch {
      this.profiles.error.set('Não foi possível encerrar o aplicativo. Tente novamente.');
    }
  }
}
