import { Component, computed, effect, input, signal } from '@angular/core';
import { FLUTE_KEYS, fluteFingerings, FluteKey } from './flute-data';

@Component({
  selector: 'app-flute-fingering',
  template: `
    <section class="fingering" aria-label="Como tocar a nota na flauta transversal">
      <div class="fingering-heading">
        <h3>Digitação · flauta</h3>
        <span>Boehm · pé em Dó</span>
      </div>
      @if (position(); as chart) {
        <div class="pitch-row">
          <strong>{{ chart.label }}</strong>
          @if (chart.variants.length > 1) {
            <select
              aria-label="Digitação da flauta"
              [value]="variant()"
              (change)="variant.set($any($event.target).value)"
            >
              @for (item of chart.variants; track item.id) {
                <option [value]="item.id">{{ item.name }}</option>
              }
            </select>
          }
        </div>
        <svg
          viewBox="0 0 424 95"
          class="diagram"
          role="group"
          [attr.aria-label]="'Chaves para ' + chart.label"
        >
          <path class="tube" d="M16 19h390v24H16z M46 43v8h285v-8" />
          <path class="mechanism" d="M59 16h247 M60 49h247" />
          @for (key of keys; track key.id) {
            <g
              role="button"
              tabindex="0"
              [attr.aria-label]="key.label + ': ' + (pressed(key.id) ? 'pressionada' : 'aberta')"
              (click)="inspect(key.id)"
              (keydown.enter)="inspect(key.id)"
              (keydown.space)="$event.preventDefault(); inspect(key.id)"
            >
              <title>{{ key.label }}: {{ pressed(key.id) ? 'pressionada' : 'aberta' }}</title>
              <rect
                class="hit-target"
                [attr.x]="key.x - 16"
                [attr.y]="key.y - 16"
                width="32"
                height="32"
              />
              <circle
                [attr.cx]="key.x"
                [attr.cy]="key.y"
                r="11"
                [class.pressed]="pressed(key.id)"
                [class.inspected]="inspected() === key.id"
              />
              <text
                [attr.x]="key.x"
                [attr.y]="key.y + 4"
                text-anchor="middle"
                [class.pressed-label]="pressed(key.id)"
              >
                {{
                  key.id === 'Gs' ? 'G♯' : key.id === 'Cs' ? 'C♯' : key.id === 'Bb' ? 'B♭' : key.id
                }}
              </text>
            </g>
          }
          <text x="103" y="92" text-anchor="middle" class="hand">Mão esquerda</text>
          <text x="263" y="92" text-anchor="middle" class="hand">Mão direita</text>
        </svg>
        <p class="key-status" aria-live="polite">
          {{ inspectedLabel() || 'Dourado: pressionada · contorno: aberta' }}
        </p>
        @if (current()?.note) {
          <p class="variant-note">{{ current()?.note }}</p>
        }
      } @else {
        <p class="unavailable">
          Selecione uma nota entre Dó4 e Fá6. Não há digitação verificada fora dessa extensão.
        </p>
      }
      <details>
        <summary>Fonte e leitura do diagrama</summary>
        <p>
          Esquema das chaves acionadas pelos dedos, não dos mecanismos internos. T: polegar em Si;
          B♭: alavanca Si bemol. Fonte: carta CC0 e guia Yamaha. A resposta varia com o instrumento
          e a embocadura.
        </p>
        <a href="/flute-fingering-cc0.jpg" target="_blank" rel="noopener"
          >Consultar carta de referência</a
        >
      </details>
    </section>
  `,
  styles: `
    .fingering {
      padding-block: var(--space-3);
      border-block: 1px solid var(--border);
      min-width: 0;
    }
    .fingering-heading,
    .pitch-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      flex-wrap: wrap;
    }
    h3 {
      margin: 0;
    }
    .fingering-heading span,
    p,
    details {
      color: var(--secondary);
      font-size: var(--text-small);
    }
    .pitch-row {
      margin-block: var(--space-2);
    }
    .pitch-row strong {
      font-size: var(--text-section);
    }
    .diagram {
      display: block;
      width: 100%;
      max-width: 540px;
      overflow: visible;
    }
    .tube {
      fill: var(--surface);
      stroke: var(--control-border);
    }
    .mechanism {
      stroke: var(--control-border);
      fill: none;
    }
    circle {
      fill: var(--color-key-open);
      stroke: var(--secondary);
      stroke-width: 1.5;
    }
    .hit-target {
      fill: transparent;
      pointer-events: all;
    }
    circle.pressed {
      fill: var(--color-key-pressed);
      stroke: var(--accent);
    }
    circle.inspected {
      stroke-width: 3;
    }
    text {
      fill: var(--primary);
      font: 10px var(--ui);
      pointer-events: none;
    }
    text.pressed-label {
      fill: var(--on-accent);
    }
    .hand {
      fill: var(--secondary);
    }
    g[role='button'] {
      cursor: pointer;
    }
    g:focus-visible {
      outline: none;
    }
    g:focus-visible circle {
      stroke: var(--color-focus);
      stroke-width: 3;
    }
    .key-status {
      min-height: 18px;
      margin: var(--space-2) 0;
    }
    details p {
      margin: var(--space-2) 0;
    }
    .variant-note,
    .unavailable {
      margin: var(--space-2) 0;
    }
  `,
})
export class FluteFingering {
  readonly pitch = input<string | null>(null);
  readonly position = computed(() => fluteFingerings(this.pitch()));
  readonly keys = FLUTE_KEYS;
  readonly variant = signal('standard');
  readonly inspected = signal<FluteKey | null>(null);
  readonly current = computed(
    () =>
      this.position()?.variants.find((v) => v.id === this.variant()) ??
      this.position()?.variants[0],
  );
  readonly inspectedLabel = computed(() => {
    const id = this.inspected();
    return id
      ? `${this.keys.find((k) => k.id === id)?.label}: ${this.pressed(id) ? 'pressionada' : 'aberta'}`
      : '';
  });
  constructor() {
    effect(() => {
      this.pitch();
      this.variant.set('standard');
      this.inspected.set(null);
    });
  }
  pressed(key: FluteKey): boolean {
    return this.current()?.pressed.includes(key) ?? false;
  }
  inspect(key: FluteKey): void {
    this.inspected.set(key);
  }
}
