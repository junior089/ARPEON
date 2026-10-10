import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class WorkspaceLayout {
  readonly explorer = signal(true);
  readonly inspector = signal(true);
  readonly inspectorWidth = signal(320);
  readonly zoom = signal(100);
  constructor() {
    try {
      const value: unknown = JSON.parse(localStorage.getItem('arpeon.layout.v1') ?? 'null');
      if (typeof value !== 'object' || value === null) return;
      const data = value as Record<string, unknown>;
      if (typeof data['explorer'] === 'boolean') this.explorer.set(data['explorer']);
      if (typeof data['inspector'] === 'boolean') this.inspector.set(data['inspector']);
      if (typeof data['inspectorWidth'] === 'number' && Number.isFinite(data['inspectorWidth']))
        this.inspectorWidth.set(Math.max(280, Math.min(460, data['inspectorWidth'])));
      if (typeof data['zoom'] === 'number' && Number.isFinite(data['zoom']))
        this.zoom.set(Math.max(50, Math.min(200, data['zoom'])));
    } catch {
      /* Storage is optional; use the default workspace. */
    }
  }
  toggle(panel: 'explorer' | 'inspector'): void {
    this[panel].update((open) => !open);
    this.persist();
  }
  setWidth(width: number): void {
    if (Number.isFinite(width)) this.inspectorWidth.set(Math.max(280, Math.min(460, width)));
    this.persist();
  }
  setZoom(zoom: number): void {
    if (Number.isFinite(zoom)) this.zoom.set(Math.max(50, Math.min(200, zoom)));
    this.persist();
  }
  reset(): void {
    this.explorer.set(true);
    this.inspector.set(true);
    this.inspectorWidth.set(320);
    this.zoom.set(100);
    this.persist();
  }
  private persist(): void {
    try {
      localStorage.setItem(
        'arpeon.layout.v1',
        JSON.stringify({
          explorer: this.explorer(),
          inspector: this.inspector(),
          inspectorWidth: this.inspectorWidth(),
          zoom: this.zoom(),
        }),
      );
    } catch {
      /* Preferences do not block editing when storage is unavailable. */
    }
  }
}
