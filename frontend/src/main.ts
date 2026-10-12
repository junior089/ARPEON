import { bootstrapApplication } from '@angular/platform-browser';
import { inject, provideAppInitializer } from '@angular/core';
import { App } from './app/app';
import { DesktopPreferences } from './app/core/desktop-preferences';

bootstrapApplication(App, {
  providers: [provideAppInitializer(() => inject(DesktopPreferences).load())],
}).catch(() => {
  document.body.textContent =
    'Não foi possível abrir o ARPEON. Feche esta aba e reinicie o aplicativo.';
});
