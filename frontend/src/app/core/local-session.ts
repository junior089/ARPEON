import { InjectionToken } from '@angular/core';

export const LOCAL_FETCH = new InjectionToken<typeof fetch>('local fetch', {
  factory: () => fetch.bind(globalThis),
});

export const SESSION_TOKEN = new InjectionToken<string>('desktop session', {
  factory: () => {
    const launchedToken = new URLSearchParams(location.hash.slice(1)).get('session') ?? '';
    history.replaceState(null, '', location.pathname);
    try {
      if (launchedToken) {
        sessionStorage.setItem('arpeon.desktop.session', launchedToken);
        return launchedToken;
      }
      return sessionStorage.getItem('arpeon.desktop.session') ?? '';
    } catch {
      return launchedToken;
    }
  },
});
