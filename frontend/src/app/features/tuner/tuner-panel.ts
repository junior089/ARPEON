import { Component, computed, inject, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { TunerState } from '../../core/tuner-contract';
import { TunerSession } from './tuner-session';

const labels: Record<TunerState, string> = {
  IDLE: 'Microfone desligado',
  STARTING: 'Aguardando a entrada',
  SILENCE: 'Silêncio — toque uma nota',
  LOW_CONFIDENCE: 'Sinal sem altura definida',
  UNSTABLE: 'Estabilizando — sustente a nota',
  TRACKING: 'Nota estável',
  ERROR: 'Entrada interrompida',
};

@Component({
  selector: 'app-tuner-panel',
  imports: [DecimalPipe],
  providers: [TunerSession],
  templateUrl: './tuner-panel.html',
  styleUrl: './tuner-panel.scss',
})
export class TunerPanel implements OnInit {
  readonly session = inject(TunerSession);
  readonly status = computed(() => labels[this.session.observation()?.state ?? 'IDLE']);
  readonly marker = computed(
    () => 50 + Math.max(-50, Math.min(50, this.session.observation()?.centsDeviation ?? 0)),
  );
  readonly intonation = computed(() => {
    const cents = this.session.observation()?.centsDeviation;
    return cents == null
      ? ''
      : Math.abs(cents) <= 5
        ? 'Afinado · ±5 cents'
        : cents < 0
          ? 'Baixo · suba a afinação'
          : 'Alto · desça a afinação';
  });
  ngOnInit(): void {
    void this.session.refreshDevices();
  }
}
