import { Component, inject } from '@angular/core';
import { ScoreSession } from '../score/score-session';

@Component({
  selector: 'app-instrument-mixer',
  templateUrl: './instrument-mixer.html',
  styleUrl: './instrument-mixer.scss',
})
export class InstrumentMixer {
  readonly session = inject(ScoreSession);

  setLevel(event: Event, name: 'volume' | 'master' | 'pan', id?: string): void {
    if (!(event.target instanceof HTMLInputElement)) return;
    const value = event.target.valueAsNumber / 100;
    void this.session.action(name, id ? { id, value } : { value });
  }

  async setPreset(event: Event): Promise<void> {
    if (!(event.target instanceof HTMLSelectElement) || !event.target.value) return;
    const control = event.target;
    await this.session.action('preset', { preset: control.value });
    control.value = '';
  }

  percent(value = 1): number {
    return Math.round(value * 100);
  }

  instrumentName(id: string): string {
    return this.session.score()?.instruments.find((instrument) => instrument.id === id)?.name ?? id;
  }
}
