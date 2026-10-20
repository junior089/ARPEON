import { Injectable, signal } from '@angular/core';

export interface EditionDraft {
  mode: 'identify' | 'withdraw';
  title: string;
  citation: string;
  referenceUrl: string;
  baseline: { title: string; citation: string; referenceUrl: string };
}
@Injectable({ providedIn: 'root' })
export class EditionDrafts {
  private readonly values = new Map<string, EditionDraft>();
  readonly pending = signal(false);
  readonly firstProjectId = signal('');
  readonly requestedProject = signal('');
  get(id: string): EditionDraft | undefined {
    return this.values.get(id);
  }
  put(id: string, draft: EditionDraft): void {
    this.values.set(id, draft);
    this.pending.set(this.values.size > 0);
    this.firstProjectId.set(this.values.keys().next().value ?? '');
  }
  clear(id: string): void {
    this.values.delete(id);
    this.pending.set(this.values.size > 0);
    this.firstProjectId.set(this.values.keys().next().value ?? '');
  }
}
