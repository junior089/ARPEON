import { describe, expect, it } from 'vitest';
import { isDevices, isObservation, TunerObservation } from './tuner-contract';

export const trackedObservation: TunerObservation = {
  state: 'TRACKING',
  frequencyHz: 440.1,
  nearestPitch: 'A4',
  octave: 4,
  centsDeviation: 0.4,
  confidence: 0.99,
  capturedAt: '2026-10-07T12:00:00Z',
  bufferLatencyMs: 85.3,
  processingLatencyMs: 3,
  analysisAgeMs: 90,
  droppedFrames: 0,
  inputOverruns: 0,
  sampleRateHz: 48000,
  referenceHz: 440,
  message: 'TRACKING',
};

describe('loopback contract', () => {
  it('accepts measurements and rejects incomplete, nonfinite and contradictory states', () => {
    expect(isObservation(trackedObservation)).toBe(true);
    expect(isObservation({ state: 'TRACKING' })).toBe(false);
    expect(isObservation({ ...trackedObservation, frequencyHz: NaN })).toBe(false);
    expect(isObservation({ ...trackedObservation, state: 'SILENCE' })).toBe(false);
    expect(isObservation({ ...trackedObservation, confidence: 2 })).toBe(false);
  });
  it('rejects malformed device discovery', () => {
    expect(isDevices([{ id: '1', name: 'Microphone', sampleRatesHz: [48000] }])).toBe(true);
    expect(isDevices([{ id: 1, name: 'Microphone' }])).toBe(false);
  });
});
