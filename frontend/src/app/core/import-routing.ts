import { Injectable, signal } from '@angular/core';

export type ImportRoute = 'direct' | 'recognition' | 'draft' | 'unsupported';
export const SCORE_FILE_ACCEPT = '.musicxml,.xml,.mxl,.mscz,.pdf,.png,.jpg,.jpeg,.mid,.midi,.wav';

export function importRoute(filename: string): ImportRoute {
  if (/\.(musicxml|xml|mxl|mscz)$/i.test(filename)) return 'direct';
  if (/\.(pdf|png|jpe?g)$/i.test(filename)) return 'recognition';
  if (/\.(mid|midi|wav)$/i.test(filename)) return 'draft';
  return 'unsupported';
}

@Injectable({ providedIn: 'root' })
export class ImportRouting {
  readonly pendingFile = signal<File | null>(null);
  take(): File | null {
    const file = this.pendingFile();
    this.pendingFile.set(null);
    return file;
  }
}
