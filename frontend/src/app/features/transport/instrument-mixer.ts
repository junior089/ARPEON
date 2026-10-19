import { Component, inject, signal } from '@angular/core';
import { ScoreSession } from '../score/score-session';

@Component({
  selector: 'app-instrument-mixer',
  templateUrl: './instrument-mixer.html',
  styleUrl: './instrument-mixer.scss',
})
export class InstrumentMixer {
  readonly session = inject(ScoreSession);
  readonly mixName = signal('');
  readonly selectedMix = signal('');
  readonly bankToDelete = signal('');

  openBanks(event: Event): void {
    if (event.target instanceof HTMLDetailsElement && event.target.open)
      void this.session.refreshSoundBanks();
  }

  selectBank(event: Event): void {
    if (event.target instanceof HTMLSelectElement)
      void this.session.action('sound-bank', { id: event.target.value });
  }

  importBank(event: Event): void {
    if (!(event.target instanceof HTMLInputElement)) return;
    const file = event.target.files?.[0];
    event.target.value = '';
    if (file) void this.session.importSoundBank(file);
  }

  setBankToDelete(event: Event): void {
    if (event.target instanceof HTMLSelectElement) this.bankToDelete.set(event.target.value);
  }

  setMixName(event: Event): void {
    if (event.target instanceof HTMLInputElement) this.mixName.set(event.target.value);
  }

  selectMix(event: Event): void {
    if (event.target instanceof HTMLSelectElement) {
      const id = event.target.value;
      this.selectedMix.set(id);
      this.mixName.set(
        this.session.transport()?.mixPresets?.find((preset) => preset.id === id)?.name ?? '',
      );
    }
  }

  async saveMix(replace: boolean): Promise<void> {
    await this.session.action('save-mix', {
      id: replace ? this.selectedMix() : '',
      name: this.mixName().trim(),
    });
    if (!this.session.error())
      this.selectedMix.set(
        this.session
          .transport()
          ?.mixPresets?.find((preset) => preset.name === this.mixName().trim())?.id ?? '',
      );
  }

  async deleteMix(): Promise<void> {
    await this.session.action('delete-mix', { id: this.selectedMix() });
    if (!this.session.error()) {
      this.selectedMix.set('');
      this.mixName.set('');
    }
  }

  setLevel(event: Event, name: 'volume' | 'master' | 'pan', id?: string): void {
    if (!(event.target instanceof HTMLInputElement)) return;
    const value = event.target.valueAsNumber / 100;
    this.session.queueLevel(name, value, id);
  }

  level(name: 'volume' | 'master' | 'pan', value: number | undefined, id?: string): number {
    return this.percent(
      this.session.mixerDraft()[`${name}:${id ?? ''}`] ?? value ?? (name === 'pan' ? 0 : 1),
    );
  }

  silenceReason(part: { mute: boolean; solo: boolean; soundStatus?: string }): string {
    if (part.mute) return 'Mute ativado';
    if (part.soundStatus === 'ERROR') return 'Timbre indisponível';
    return this.session.transport()?.parts.some((channel) => channel.solo) && !part.solo
      ? 'Silenciado por outro solo'
      : '';
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
