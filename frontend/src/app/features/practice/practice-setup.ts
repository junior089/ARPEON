import { Injectable } from '@angular/core';
import type { PracticeOptions } from './practice-session';

@Injectable({ providedIn: 'root' })
export class PracticeSetup {
  options: PracticeOptions = {
    kind: 'NOTE',
    keyId: 'C_MAJOR',
    octave: 4,
    scaleType: 'MAJOR',
    bpm: 80,
    loop: false,
    progressiveStep: 0,
  };
}

export function practiceSetupErrors(
  options: PracticeOptions,
): Partial<Record<keyof PracticeOptions, string>> {
  const errors: Partial<Record<keyof PracticeOptions, string>> = {};
  if (!['NOTE', 'LONG_TONE', 'SCALE', 'ARPEGGIO', 'RHYTHM'].includes(options.kind))
    errors.kind = 'Escolha um exercício disponível.';
  if (
    !['C_MAJOR', 'G_MAJOR', 'F_MAJOR', 'D_MAJOR', 'A_MINOR', 'E_MINOR', 'D_MINOR'].includes(
      options.keyId,
    )
  )
    errors.keyId = 'Escolha uma tonalidade disponível.';
  if (!Number.isInteger(options.bpm) || options.bpm < 40 || options.bpm > 180)
    errors.bpm = 'Andamento: use um inteiro entre 40 e 180 BPM.';
  if (![4, 5].includes(options.octave)) errors.octave = 'Escolha a oitava 4 ou 5.';
  if (
    !Number.isInteger(options.progressiveStep) ||
    options.progressiveStep < 0 ||
    options.progressiveStep > 20
  )
    errors.progressiveStep = 'Aumento por repetição: use um inteiro entre 0 e 20 BPM.';
  const minor = options.keyId.endsWith('_MINOR');
  if (
    !['MAJOR', 'NATURAL_MINOR', 'HARMONIC_MINOR', 'MELODIC_MINOR', 'CHROMATIC'].includes(
      options.scaleType,
    ) ||
    (options.scaleType === 'MAJOR' && minor) ||
    (options.scaleType.includes('MINOR') && !minor)
  )
    errors.scaleType = 'Escolha um tipo de escala compatível com a tonalidade.';
  return errors;
}
