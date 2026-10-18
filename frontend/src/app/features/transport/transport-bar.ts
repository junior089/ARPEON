import { Component, computed, effect, inject, signal } from '@angular/core';
import { InstrumentMixer } from './instrument-mixer';
import { Icon } from '../../shared/icon';
import { ScoreSession } from '../score/score-session';

@Component({
  selector: 'app-transport-bar',
  imports: [Icon, InstrumentMixer],
  templateUrl: './transport-bar.html',
  styleUrl: './transport-bar.scss',
})
export class TransportBar {
  readonly session = inject(ScoreSession);
  readonly mixerOpen = signal(false);
  readonly seeking = signal(false);
  readonly pendingTick = signal(0);
  readonly draftBpm = signal<number | null>(null);
  readonly settingsOpen = signal(false);
  readonly loopOpen = signal(false);
  readonly draftLoopStart = signal<number | null>(null);
  readonly draftLoopEnd = signal<number | null>(null);
  readonly loopStart = computed(
    () => this.draftLoopStart() ?? this.session.transport()?.loop?.startMeasureIndex ?? 0,
  );
  readonly loopEnd = computed(
    () =>
      this.draftLoopEnd() ??
      this.session.transport()?.loop?.endMeasureIndex ??
      Math.max(0, (this.session.score()?.measures.length ?? 1) - 1),
  );
  readonly validLoop = computed(
    () =>
      Number.isInteger(this.loopStart()) &&
      Number.isInteger(this.loopEnd()) &&
      this.loopStart() >= 0 &&
      this.loopEnd() >= this.loopStart() &&
      this.loopEnd() < (this.session.score()?.measures.length ?? 0),
  );
  private documentKey = '';

  constructor() {
    effect(() => {
      const key = this.session.score()?.sha256 ?? '';
      if (key === this.documentKey) return;
      this.documentKey = key;
      this.draftLoopStart.set(null);
      this.draftLoopEnd.set(null);
      this.loopOpen.set(false);
    });
  }

  async togglePlayback(): Promise<void> {
    const state = this.session.transport();
    if (state) await this.session.action(state.status === 'PLAYING' ? 'pause' : 'play');
  }

  async jump(direction: -1 | 1, edge = false): Promise<void> {
    const state = this.session.transport();
    const measures = this.session.score()?.measures;
    if (!state || !measures?.length || this.session.busy()) return;
    const min = state.loop?.startMeasureIndex ?? 0;
    const max = state.loop?.endMeasureIndex ?? measures.length - 1;
    const index = edge
      ? direction < 0
        ? min
        : max
      : Math.max(min, Math.min(max, state.measureIndex + direction));
    const tick = measures[index]?.startTick;
    if (tick !== undefined) await this.session.action('seek', { tick });
  }

  setLoopPoint(point: 'start' | 'end', event: Event): void {
    if (!(event.target instanceof HTMLSelectElement)) return;
    (point === 'start' ? this.draftLoopStart : this.draftLoopEnd).set(Number(event.target.value));
  }

  async applyLoop(enabled: boolean): Promise<void> {
    if (!this.validLoop() && enabled) return;
    const current = this.session.transport()?.loop;
    await this.session.action('loop', {
      startMeasureIndex: enabled ? this.loopStart() : (current?.startMeasureIndex ?? 0),
      endMeasureIndex: enabled ? this.loopEnd() : (current?.endMeasureIndex ?? 0),
      enabled,
    });
  }
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
