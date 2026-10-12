import { inject, Injectable, signal } from '@angular/core';
import { DesktopPreferences, WorkspaceMode } from '../core/desktop-preferences';

@Injectable({ providedIn: 'root' })
export class WorkspaceLayout {
  private readonly preferences = inject(DesktopPreferences);
  private readonly initial = this.preferences.snapshot();
  readonly explorer = signal(this.initial.explorer);
  readonly inspector = signal(this.initial.inspector);
  readonly inspectorWidth = signal(this.initial.inspectorWidth);
  readonly zoom = signal(this.initial.zoom);
  readonly mode = signal<WorkspaceMode>(this.initial.mode);
  readonly scoreDark = signal(this.initial.scoreDark);
  restorePanels(): void {
    const saved = this.preferences.snapshot();
    this.explorer.set(saved.explorer);
    this.inspector.set(saved.inspector);
  }
  toggle(panel: 'explorer' | 'inspector'): void {
    this[panel].update((open) => !open);
    this.mode.set('custom');
    this.persist();
  }
  setMode(mode: Exclude<WorkspaceMode, 'custom'>): void {
    this.mode.set(mode);
    this.explorer.set(mode === 'edit');
    this.inspector.set(mode !== 'play');
    this.persist();
  }
  toggleScoreTheme(): void {
    this.scoreDark.update((dark) => !dark);
    this.preferences.update({ scoreDark: this.scoreDark() });
  }
  setWidth(width: number): void {
    if (Number.isFinite(width)) {
      this.inspectorWidth.set(Math.round(Math.max(280, Math.min(460, width))));
      this.preferences.update({ inspectorWidth: this.inspectorWidth() });
    }
  }
  setZoom(zoom: number): void {
    if (Number.isFinite(zoom)) {
      this.zoom.set(Math.round(Math.max(50, Math.min(200, zoom))));
      this.preferences.update({ zoom: this.zoom() });
    }
  }
  reset(): void {
    this.explorer.set(true);
    this.inspector.set(true);
    this.inspectorWidth.set(320);
    this.mode.set('edit');
    this.zoom.set(100);
    this.persist();
  }
  private persist(): void {
    this.preferences.update({
      explorer: this.explorer(),
      inspector: this.inspector(),
      inspectorWidth: this.inspectorWidth(),
      zoom: this.zoom(),
      mode: this.mode(),
    });
  }
}
