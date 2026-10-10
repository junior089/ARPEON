import { Component, computed, input } from '@angular/core';

const STEPS: Record<string, number> = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };
const NAMES: Record<string, string> = {
  C: 'Dó',
  D: 'Ré',
  E: 'Mi',
  F: 'Fá',
  G: 'Sol',
  A: 'Lá',
  B: 'Si',
};

export interface FluteChartPosition {
  label: string;
  position: string;
}

export function fluteChartPosition(pitch: string | null): FluteChartPosition | null {
  const match = /^([A-G])(##|♯♯|bb|♭♭|#|♯|b|♭)?(-?\d+)$/.exec(pitch ?? '');
  if (!match) return null;
  const step = STEPS[match[1] ?? ''];
  if (step === undefined) return null;
  const marks = match[2] ?? '';
  const alteration =
    marks.startsWith('#') || marks.startsWith('♯') ? marks.length : marks ? -marks.length : 0;
  const midi = (Number(match[3]) + 1) * 12 + step + alteration;
  const offset = midi - 60;
  if (offset < 0 || offset > 29) return null; // The local chart covers C4–F6.
  const column = Math.floor(offset / 12);
  const row = offset % 12;
  const accidental = alteration > 0 ? '♯'.repeat(alteration) : '♭'.repeat(-alteration);
  return {
    label: `${NAMES[match[1] ?? '']}${accidental} ${match[3]}`,
    position: `${-column * 287}px ${-row * 78}px`,
  };
}

@Component({
  selector: 'app-flute-fingering',
  template: `
    <section class="fingering" aria-label="Como tocar a nota na flauta transversal">
      <div class="fingering-copy">
        <p class="fingering-label">Digitação · flauta transversal</p>
        @if (position(); as chart) {
          <h3>{{ chart.label }}</h3>
          <p>
            As teclas escuras na figura ficam pressionadas. Confira a nota escrita e a oitava antes
            de tocar.
          </p>
        } @else {
          <h3>Digitação indisponível</h3>
          <p>
            A carta local cobre Dó 4 a Fá 6. Selecione uma nota nesse intervalo para ver a posição.
          </p>
        }
      </div>
      @if (position(); as chart) {
        <div
          class="diagram"
          role="img"
          [attr.aria-label]="'Digitação para ' + chart.label + ': teclas escuras pressionadas'"
        >
          <div class="chart-crop" [style.background-position]="chart.position"></div>
        </div>
      }
      <a href="/flute-fingering-cc0.jpg" target="_blank" rel="noopener">Ver carta completa ↗</a>
    </section>
  `,
  styles: `
    .fingering {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: var(--space-3) var(--space-4);
      padding: var(--space-4) 0;
      border-block: 1px solid var(--border);
    }
    .fingering h3 {
      margin: 0.35rem 0;
      font-size: var(--text-section);
      font-weight: 600;
      letter-spacing: -0.04em;
    }
    .fingering p:not(.fingering-label) {
      max-width: 48ch;
      color: var(--secondary);
      font-size: var(--text-small);
      line-height: 1.55;
      margin: 0;
    }
    .fingering .fingering-label {
      font-size: var(--text-small);
      color: var(--secondary);
      margin: 0;
    }
    .diagram {
      width: 287px;
      max-width: 100%;
      height: 78px;
      overflow: hidden;
      background: var(--color-reference-paper);
      border-radius: 3px;
    }
    .chart-crop {
      width: 287px;
      height: 78px;
      background-image: url('/flute-fingering-cc0.jpg');
      background-repeat: no-repeat;
    }
    .fingering a {
      color: var(--accent);
      font-size: var(--text-small);
      text-underline-offset: 3px;
      grid-column: 1 / -1;
    }
    @media (max-width: 650px) {
      .fingering {
        grid-template-columns: 1fr;
        padding: var(--space-3) 0;
      }
      .fingering a {
        grid-column: auto;
      }
    }
  `,
})
export class FluteFingering {
  readonly pitch = input<string | null>(null);
  readonly position = computed(() => fluteChartPosition(this.pitch()));
}
