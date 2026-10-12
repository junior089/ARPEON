import { describe, expect, it } from 'vitest';
import { analysisDefaults, analysisSetupErrors, AnalysisSettings } from './analysis-setup';

describe('analysis setup validation', () => {
  it('accepts the defaults and the supported range boundaries', () => {
    expect(analysisSetupErrors(analysisDefaults)).toEqual({});
    expect(
      analysisSetupErrors({
        alignmentWindowMs: 150,
        timingToleranceMs: 20,
        pitchToleranceCents: 10,
        rhythmTolerancePercent: 5,
        latencyCompensationMs: 0,
      }),
    ).toEqual({});
    expect(
      analysisSetupErrors({
        alignmentWindowMs: 1000,
        timingToleranceMs: 1000,
        pitchToleranceCents: 100,
        rhythmTolerancePercent: 60,
        latencyCompensationMs: 500,
      }),
    ).toEqual({});
  });
  it('requires timing tolerance to fit inside the alignment window', () => {
    expect(
      analysisSetupErrors({ ...analysisDefaults, alignmentWindowMs: 150, timingToleranceMs: 151 }),
    ).toHaveProperty('timingToleranceMs');
  });
  it.each([NaN, Infinity, -1, 0.5])(
    'rejects empty, non-finite, negative and fractional values (%s)',
    (value) => {
      for (const field of Object.keys(analysisDefaults) as (keyof AnalysisSettings)[]) {
        expect(analysisSetupErrors({ ...analysisDefaults, [field]: value })).toHaveProperty(field);
      }
    },
  );
  it('identifies every independently out-of-range criterion', () => {
    expect(
      Object.keys(
        analysisSetupErrors({
          alignmentWindowMs: 1001,
          timingToleranceMs: 19,
          pitchToleranceCents: 101,
          rhythmTolerancePercent: 61,
          latencyCompensationMs: 501,
        }),
      ),
    ).toHaveLength(5);
  });
});
