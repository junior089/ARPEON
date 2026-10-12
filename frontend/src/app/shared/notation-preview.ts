import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  effect,
  input,
  signal,
} from '@angular/core';
import type { OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import { scoreAppearance } from './score-appearance';

@Component({
  selector: 'app-notation-preview',
  template: `
    @if (loading()) {
      <p role="status">Desenhando partitura…</p>
    }
    @if (error()) {
      <p class="error" role="alert">{{ error() }}</p>
      <button type="button" (click)="render()">Tentar desenhar novamente</button>
    }
    <div #sheet class="preview-sheet" role="img" [attr.aria-label]="label()"></div>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .preview-sheet {
      background: var(--paper);
      color: var(--ink);
      overflow: auto;
      min-height: 120px;
      padding: var(--space-2);
    }
    p {
      font-size: var(--text-small);
    }
  `,
})
export class NotationPreview implements AfterViewInit, OnDestroy {
  readonly xml = input.required<string>();
  readonly label = input('Prévia da partitura');
  readonly zoom = input(100);
  readonly loading = signal(false);
  readonly error = signal('');
  @ViewChild('sheet') private sheet?: ElementRef<HTMLDivElement>;
  private renderer?: OpenSheetMusicDisplay;
  private observer?: ResizeObserver;
  private generation = 0;
  private disposed = false;
  private frame = 0;
  constructor() {
    effect(() => {
      this.xml();
      this.zoom();
      if (this.sheet) void this.render();
    });
  }
  ngAfterViewInit(): void {
    void this.render();
    if (typeof ResizeObserver !== 'undefined' && this.sheet) {
      let width = 0;
      this.observer = new ResizeObserver(([entry]) => {
        if (!entry || Math.abs(width - entry.contentRect.width) < 2) return;
        width = entry.contentRect.width;
        cancelAnimationFrame(this.frame);
        this.frame = requestAnimationFrame(() => this.renderer?.render());
      });
      this.observer.observe(this.sheet.nativeElement);
    }
  }
  async render(): Promise<void> {
    const element = this.sheet?.nativeElement;
    if (!element || !this.xml() || this.disposed) return;
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set('');
    let candidate: OpenSheetMusicDisplay | undefined;
    try {
      const bundle = await import('opensheetmusicdisplay');
      if (generation !== this.generation) return;
      this.renderer?.clear();
      this.renderer = undefined;
      element.replaceChildren();
      candidate = new (bundle.default ?? bundle).OpenSheetMusicDisplay(element, {
        ...scoreAppearance(false),
        backend: 'svg',
        autoResize: false,
        drawTitle: false,
        drawingParameters: 'compacttight',
      });
      await candidate.load(this.xml());
      if (generation !== this.generation || this.disposed) {
        candidate.clear();
        return;
      }
      candidate.Zoom = this.zoom() / 100;
      candidate.render();
      this.renderer = candidate;
    } catch {
      candidate?.clear();
      if (generation === this.generation && !this.disposed)
        this.error.set('Não foi possível desenhar a prévia. Tente novamente antes de confirmar.');
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
  ngOnDestroy(): void {
    this.disposed = true;
    ++this.generation;
    cancelAnimationFrame(this.frame);
    this.observer?.disconnect();
    this.renderer?.clear();
  }
}
