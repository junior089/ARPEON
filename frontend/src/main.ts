import { bootstrapApplication } from '@angular/platform-browser';
import { EnvironmentInjector, inject, provideAppInitializer } from '@angular/core';
import { ProfileShell } from './app/profile-shell';
import { DesktopPreferences } from './app/core/desktop-preferences';
import { StudyProfiles } from './app/core/study-profiles';

bootstrapApplication(ProfileShell, {
  providers: [
    provideAppInitializer(() => {
      const profiles = inject(StudyProfiles);
      const environment = inject(EnvironmentInjector);
      return profiles.load().then(() => {
        if (!profiles.needsSelection() && (profiles.account() || profiles.legacy()))
          return environment.get(DesktopPreferences).load();
        return undefined;
      });
    }),
  ],
}).catch(() => {
  document.body.textContent =
    'Não foi possível abrir o ARPEON. Feche esta aba e reinicie o aplicativo.';
});
