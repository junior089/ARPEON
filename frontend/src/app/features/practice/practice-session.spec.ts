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

  const emptyPage = {
    entries: [],
    page: 0,
    pageSize: 20,
    matchingCount: 0,
    totalCount: 0,
    completedCount: 0,
  };

  it('keeps practice available when history fails, and retries history separately', async () => {
    fetcher.mockImplementation(async (path) =>
      String(path).startsWith('/api/practice/history')
        ? Response.json({ error: 'Arquivo local ilegível.' }, { status: 500 })
        : Response.json(exercise),
    );
    expect(await session.load()).toEqual(exercise);
    expect(session.error()).toBe('');
    expect(session.historyError()).toBe('Arquivo local ilegível.');
    fetcher.mockResolvedValue(Response.json(emptyPage));
    expect(await session.retryHistory()).toBe(true);
    expect(session.historyError()).toBe('');
    expect(session.historyPage()).toEqual(emptyPage);
  });

  it('aborts an old history request and ignores its late response', async () => {
    let resolveOld: (value: Response) => void = () => undefined;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          resolveOld = resolve;
        }),
    );
    const old = session.loadHistory(0, { kind: 'NOTE', outcome: '', from: '', until: '' });
    const oldSignal = fetcher.mock.calls[0]?.[1]?.signal;
    fetcher.mockResolvedValueOnce(Response.json({ ...emptyPage, totalCount: 12 }));
    expect(await session.loadHistory(0, { kind: 'SCALE', outcome: '', from: '', until: '' })).toBe(
      true,
    );
    expect(oldSignal?.aborted).toBe(true);
    resolveOld(Response.json(emptyPage));
    expect(await old).toBe(false);
    expect(session.historyPage()?.totalCount).toBe(12);
    expect(session.historyFilters().kind).toBe('SCALE');
    expect(session.historyBusy()).toBe(false);
  });

  it('rejects inconsistent page metadata without replacing practice state', async () => {
    session.view.set(exercise);
    fetcher.mockResolvedValue(Response.json({ ...emptyPage, matchingCount: 25 }));
    expect(await session.loadHistory()).toBe(false);
    expect(session.historyPage()).toBeNull();
    expect(session.historyError()).toContain('inválida');
    expect(session.view()).toEqual(exercise);
  });

  it('refreshes the first bounded page with existing filters after a save', async () => {
    const filters = { kind: 'LONG_TONE', outcome: 'completed', from: '', until: '' };
    session.historyFilters.set(filters);
    fetcher
      .mockResolvedValueOnce(
        Response.json({
          summary: {
            id: 'saved-1',
            kind: 'NOTE',
            keyId: 'C_MAJOR',
            startedAt: '2026-10-11T12:00:00Z',
            endedAt: '2026-10-11T12:01:00Z',
            completed: false,
            targets: 8,
            reached: 2,
            heldMs: 0,
            stabilityCents: null,
            interruptions: 0,
            bpm: 80,
          },
        }),
      )
      .mockResolvedValueOnce(Response.json(exercise))
      .mockResolvedValueOnce(Response.json({ ...emptyPage, totalCount: 1 }));
    await session.finish();
    expect(session.savedAttempt()).toBe(true);
    expect(session.historyFilters()).toEqual(filters);
    const request = new URL(String(fetcher.mock.calls[2]?.[0]), 'http://localhost');
    expect(request.searchParams.get('page')).toBe('0');
    expect(request.searchParams.get('pageSize')).toBe('20');
    expect(request.searchParams.get('kind')).toBe('LONG_TONE');
    expect(session.error()).toBe('');
  });

  it('does not publish a history response after its panel has been destroyed', async () => {
    let resolve: (value: Response) => void = () => undefined;
    fetcher.mockImplementation(
      () =>
        new Promise<Response>((done) => {
          resolve = done;
        }),
    );
    const pending = session.loadHistory();
    session.ngOnDestroy();
    resolve(Response.json(emptyPage));
    expect(await pending).toBe(false);
    expect(session.historyPage()).toBeNull();
  });

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
