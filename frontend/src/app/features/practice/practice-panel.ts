import { scoreAppearance } from '../../shared/score-appearance';
import { DecimalPipe } from '@angular/common';
import {
  AfterViewInit,
  Component,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  computed,
  signal,
  ViewChild,
} from '@angular/core';
import type { OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import { TunerSession } from '../tuner/tuner-session';
import { PracticeOptions, PracticeSession } from './practice-session';
import { FluteFingering } from '../fingering/flute-fingering';

@Component({
  selector: 'app-practice-panel',
  imports: [DecimalPipe, FluteFingering],
  providers: [PracticeSession, TunerSession],
  templateUrl: './practice-panel.html',
  styleUrl: './practice-panel.scss',
})
export class PracticePanel implements OnInit, AfterViewInit, OnDestroy {
  readonly session = inject(PracticeSession);
  readonly microphone = inject(TunerSession);
  readonly inspectedPitch = signal<string | null>(null);
  readonly guidePitch = computed(
    () =>
      this.inspectedPitch() ??
      this.session.view()?.targets[this.session.view()?.targetIndex ?? 0]?.pitch ??
      null,
  );
  kind = 'NOTE';
  keyId = 'C_MAJOR';
  octave = 4;
  scaleType = 'MAJOR';
  bpm = 80;
  loop = false;
  progressiveStep = 0;
  readonly feedbackLabels: Record<string, string> = {
    READY: 'Pronto',
    LISTENING: 'Escutando',
    SILENCE: 'Silêncio',
    LOW_CONFIDENCE: 'Sinal incerto',
    UNSTABLE: 'Altura instável',
    CORRECT: 'Nota correspondente',
    INCORRECT: 'Nota diferente',
    COMPLETE: 'Meta alcançada',
    ON_TIME: 'No tempo',
    EARLY: 'Adiantado',
    LATE: 'Atrasado',
  };
  @ViewChild('sheet') private sheet?: ElementRef<HTMLDivElement>;
  private renderer?: OpenSheetMusicDisplay;
  private renderGeneration = 0;
  private destroyed = false;

  ngOnInit(): void {
    void this.microphone.refreshDevices();
  }

  ngAfterViewInit(): void {
    void this.session.load().then((current) => {
      if (current) void this.render(current.xml);
    });
  }

  text(event: Event): string {
    const target = event.target;
    return target instanceof HTMLSelectElement || target instanceof HTMLInputElement
      ? target.value
      : '';
  }

  number(event: Event): number {
    return Number(this.text(event));
  }

  feedbackLabel(state: string): string {
    return this.feedbackLabels[state] ?? state;
  }

  setKey(event: Event): void {
    this.keyId = this.text(event);
    this.scaleType = this.keyId.endsWith('_MINOR') ? 'NATURAL_MINOR' : 'MAJOR';
  }

  async generate(): Promise<void> {
    const options: PracticeOptions = {
      kind: this.kind,
      keyId: this.keyId,
      octave: this.octave,
      scaleType: this.scaleType,
      bpm: this.bpm,
      loop: this.loop,
      progressiveStep: this.progressiveStep,
    };
    if (await this.session.generate(options)) {
      this.inspectedPitch.set(null);
      await this.render(this.session.view()?.xml ?? '');
    }
  }

  async finish(): Promise<void> {
    await this.session.finish();
    if (this.microphone.active()) await this.microphone.stop();
  }

  private async render(xml: string): Promise<void> {
    if (!this.sheet || !xml || this.destroyed) return;
    const generation = ++this.renderGeneration;
    try {
      const bundle = await import('opensheetmusicdisplay');
      if (generation !== this.renderGeneration || this.destroyed) return;
      const Constructor = (bundle.default ?? bundle).OpenSheetMusicDisplay;
      this.renderer?.clear();
      this.sheet.nativeElement.replaceChildren();
      const renderer = new Constructor(this.sheet.nativeElement, {
        backend: 'svg',
        ...scoreAppearance(),
        drawTitle: false,
        autoResize: true,
      });
      await renderer.load(xml);
      if (generation !== this.renderGeneration || this.destroyed) {
        renderer.clear();
        return;
      }
      renderer.render();
      this.renderer = renderer;
    } catch {
      if (!this.destroyed)
        this.session.error.set('O exercício foi criado, mas a pauta não pôde ser desenhada.');
    }
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    ++this.renderGeneration;
    this.renderer?.clear();
  }
}
