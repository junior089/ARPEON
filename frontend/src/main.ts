import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';

bootstrapApplication(App).catch(() => {
  document.body.textContent =
    'Não foi possível abrir o ARPEON. Feche esta aba e reinicie o aplicativo.';
});
