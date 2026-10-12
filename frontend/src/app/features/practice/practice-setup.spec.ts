import { describe, expect, it } from 'vitest';
import { PracticeOptions } from './practice-session';
import { practiceSetupErrors } from './practice-setup';

const defaults: PracticeOptions = {
  kind: 'NOTE',
  keyId: 'C_MAJOR',
  octave: 4,
  scaleType: 'MAJOR',
  bpm: 80,
  loop: false,
  progressiveStep: 0,
};
describe('practice setup validation', () => {
  it('accepts playable settings including tempo and repeat boundaries', () => {
    expect(practiceSetupErrors(defaults)).toEqual({});
    expect(practiceSetupErrors({ ...defaults, bpm: 40 })).toEqual({});
    expect(
      practiceSetupErrors({ ...defaults, bpm: 180, progressiveStep: 20, octave: 5, loop: true }),
    ).toEqual({});
  });
  it.each([NaN, Infinity, 39, 181, 80.5])('rejects invalid tempo (%s)', (bpm) => {
    expect(practiceSetupErrors({ ...defaults, bpm })).toHaveProperty('bpm');
  });
  it.each([NaN, Infinity, -1, 21, 0.5])(
    'rejects invalid repeat increments even when repeating is off (%s)',
    (progressiveStep) => {
      expect(practiceSetupErrors({ ...defaults, progressiveStep })).toHaveProperty(
        'progressiveStep',
      );
    },
  );
  it('checks scale compatibility and allows chromatic exercises in either key family', () => {
    expect(practiceSetupErrors({ ...defaults, keyId: 'D_MINOR' })).toHaveProperty('scaleType');
    expect(practiceSetupErrors({ ...defaults, scaleType: 'HARMONIC_MINOR' })).toHaveProperty(
      'scaleType',
    );
    expect(
      practiceSetupErrors({ ...defaults, keyId: 'D_MINOR', scaleType: 'MELODIC_MINOR' }),
    ).toEqual({});
    expect(practiceSetupErrors({ ...defaults, keyId: 'D_MINOR', scaleType: 'CHROMATIC' })).toEqual(
      {},
    );
  });
  it('rejects unknown exercise types, keys, scale types and unsupported octaves', () => {
    expect(
      Object.keys(
        practiceSetupErrors({
          ...defaults,
          kind: 'UNKNOWN',
          keyId: 'UNKNOWN',
          octave: 3,
          scaleType: 'UNKNOWN',
        }),
      ).sort(),
    ).toEqual(['keyId', 'kind', 'octave', 'scaleType']);
  });
});
