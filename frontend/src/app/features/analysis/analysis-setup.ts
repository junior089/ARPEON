export interface AnalysisSettings {
  alignmentWindowMs: number;
  timingToleranceMs: number;
  pitchToleranceCents: number;
  rhythmTolerancePercent: number;
  latencyCompensationMs: number;
}

export const analysisDefaults: Readonly<AnalysisSettings> = {
  alignmentWindowMs: 450,
  timingToleranceMs: 120,
  pitchToleranceCents: 50,
  rhythmTolerancePercent: 20,
  latencyCompensationMs: 0,
};

@Injectable({ providedIn: 'root' })
export class AnalysisSetup {
  readonly alignmentWindowMs = signal(analysisDefaults.alignmentWindowMs);
  readonly timingToleranceMs = signal(analysisDefaults.timingToleranceMs);
  readonly pitchToleranceCents = signal(analysisDefaults.pitchToleranceCents);
  readonly rhythmTolerancePercent = signal(analysisDefaults.rhythmTolerancePercent);
  readonly latencyCompensationMs = signal(analysisDefaults.latencyCompensationMs);
}

export function analysisSetupErrors(
  settings: AnalysisSettings,
): Partial<Record<keyof AnalysisSettings, string>> {
  const errors: Partial<Record<keyof AnalysisSettings, string>> = {};
  const within = (value: number, min: number, max: number) =>
    Number.isInteger(value) && value >= min && value <= max;
  if (!within(settings.alignmentWindowMs, 150, 1000))
    errors.alignmentWindowMs = 'Janela de alinhamento: use um inteiro entre 150 e 1.000 ms.';
  if (!within(settings.timingToleranceMs, 20, settings.alignmentWindowMs))
    errors.timingToleranceMs =
      'Tolerância de entrada: use um inteiro a partir de 20 ms, sem ultrapassar a janela de alinhamento.';
  if (!within(settings.pitchToleranceCents, 10, 100))
    errors.pitchToleranceCents = 'Tolerância de altura: use um inteiro entre 10 e 100 cents.';
  if (!within(settings.rhythmTolerancePercent, 5, 60))
    errors.rhythmTolerancePercent = 'Tolerância de ritmo/duração: use um inteiro entre 5 e 60%.';
  if (!within(settings.latencyCompensationMs, 0, 500))
    errors.latencyCompensationMs = 'Compensação de latência: use um inteiro entre 0 e 500 ms.';
  return errors;
}
import { Injectable, signal } from '@angular/core';
