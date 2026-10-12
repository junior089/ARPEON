import { InjectionToken } from '@angular/core';

export const CAPTURE_OWNER = new InjectionToken<string>('capture owner', {
  factory: () => crypto.randomUUID(),
});

export const captureOwnerProvider = {
  provide: CAPTURE_OWNER,
  useFactory: () => crypto.randomUUID(),
};
