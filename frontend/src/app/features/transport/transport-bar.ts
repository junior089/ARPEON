import { Component, computed, inject, signal } from '@angular/core';
import { ScoreSession } from '../score/score-session';

@Component({
  selector: 'app-transport-bar',
  templateUrl: './transport-bar.html',
  styleUrl: './transport-bar.scss',
})
export class TransportBar {
  readonly session = inject(ScoreSession);
  readonly seeking = signal(false);
  readonly pendingTick = signal(0);
  readonly draftBpm = signal<number | null>(null);
  readonly canApplyBpm = computed(() => {
    const value = this.draftBpm();
    return value !== null && Number.isFinite(value) && value >= 30 && value <= 300;
  });

  setSeek(event: Event): void {
    if (event.target instanceof HTMLInputElement) {
      this.seeking.set(true);
      this.pendingTick.set(event.target.valueAsNumber);
    }
  }

  commitSeek(event: Event): void {
    if (!(event.target instanceof HTMLInputElement)) return;
    const tick = event.target.valueAsNumber;
    this.seeking.set(false);
    void this.session.action('seek', { tick });
  }

  setDraftBpm(event: Event): void {
    if (!(event.target instanceof HTMLInputElement)) return;
    this.draftBpm.set(event.target.valueAsNumber);
  }

  async applyBpm(): Promise<void> {
    const bpm = this.draftBpm();
    if (bpm === null || !Number.isFinite(bpm) || bpm < 30 || bpm > 300) return;
    await this.session.action('bpm', { bpm });
    if (!this.session.error()) this.draftBpm.set(null);
  }
}
