import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { PracticeSession } from './practice-session';

describe('PracticeSession', () => {
  const fetcher = vi.fn<typeof fetch>();
  let session: PracticeSession;
  const exercise = {
    kind: 'NOTE',
    scaleType: 'MAJOR',
    keyId: 'C_MAJOR',
    octave: 4,
    bpm: 80,
    longToneTargetMs: 0,
    iteration: 1,
    loop: false,
    progressiveStep: 0,
    fingeringDatasetId: 'flute-cc0-chart-v1',
    xml: '<score-partwise/>',
    targets: [{ pitch: 'C4', midi: 60, startTick: 0, durationTicks: 480 }],
    targetIndex: 0,
    active: false,
    attemptOpen: false,
    feedback: {
      state: 'READY',
      target: 'C4',
      detected: null,
      cents: null,
      confidence: 0,
      responseMs: null,
      heldMs: null,
      stabilityCents: null,
      interruptions: 0,
      timingErrorMs: null,
    },
    transport: { status: 'STOPPED', tick: 0, totalTicks: 1920 },
  };

  beforeEach(() => {
    fetcher.mockReset();
    TestBed.configureTestingModule({
      providers: [
        PracticeSession,
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'local-test' },
      ],
    });
    session = TestBed.inject(PracticeSession);
  });
  afterEach(() => TestBed.resetTestingModule());

  it('generates a validated exercise through the local bridge', async () => {
    fetcher.mockResolvedValue(Response.json(exercise));
    expect(
      await session.generate({
        kind: 'NOTE',
        keyId: 'C_MAJOR',
        octave: 4,
        scaleType: 'MAJOR',
        bpm: 80,
        loop: false,
        progressiveStep: 0,
      }),
    ).toBe(true);
    expect(session.view()?.targets[0]?.pitch).toBe('C4');
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/practice/create');
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({
      'X-Arpeon-Session': 'local-test',
    });
    expect(fetcher.mock.calls[0]?.[1]?.body).toContain('"progressiveStep":0');
  });

  it('rejects an incomplete response instead of displaying fake practice data', async () => {
    fetcher.mockResolvedValue(Response.json({ ...exercise, targets: [{ pitch: 'C4' }] }));
    expect(
      await session.generate({
        kind: 'NOTE',
        keyId: 'C_MAJOR',
        octave: 4,
        scaleType: 'MAJOR',
        bpm: 80,
        loop: false,
        progressiveStep: 0,
      }),
    ).toBe(false);
    expect(session.view()).toBeNull();
    expect(session.error()).toContain('inválida');
  });
});
