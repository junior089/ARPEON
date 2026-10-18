import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { ScoreSession } from './score-session';
import type { ScoreView, TransportState } from '../../core/score-contract';

describe('ScoreSession', () => {
  const fetcher = vi.fn<typeof fetch>();
  const transport: TransportState = {
    status: 'STOPPED',
    tick: 0,
    totalTicks: 3840,
    measureIndex: 0,
    measureNumber: '1',
    bpm: 72,
    startingBpm: 72,
    metronome: false,
    countIn: false,
    countInBeatsLeft: 0,
    activePartId: 'P1',
    parts: [{ id: 'P1', name: 'Melody', mute: false, solo: false, instrumentId: 'concert' }],
  };
  const score: ScoreView = {
    title: 'First notes',
    filename: 'first.musicxml',
    sha256: 'a'.repeat(64),
    parserVersion: 'musicxml-m0-v1',
    sourceKind: 'MUSICXML',
    importWarnings: [],
    source: null,
    measures: [{ index: 0, number: '1', startTick: 0, durationTicks: 1920 }],
    parts: [
      {
        id: 'P1',
        name: 'Melody',
        measures: [
          {
            index: 0,
            number: '1',
            events: [
              {
                index: 0,
                kind: 'NOTE',
                pitch: 'C4',
                step: 'C',
                alter: 0,
                octave: 4,
                numerator: 1,
                denominator: 4,
                tieStart: false,
                tieStop: false,
              },
            ],
          },
        ],
      },
    ],
    revision: 0,
    canUndo: false,
    canRedo: false,
    keyId: null,
    rangeWarnings: [],
    instruments: [
      {
        id: 'concert',
        name: 'Som genérico',
        low: 'C-1',
        high: 'G9',
        comfortableLow: 'C-1',
        comfortableHigh: 'G9',
        soundingSemitones: 0,
        midiProgram: 0,
      },
    ],
    keys: [{ id: 'C_MAJOR', label: 'C maior', minor: false }],
  };
  let session: ScoreSession;

  beforeEach(() => {
    fetcher.mockReset();
    TestBed.configureTestingModule({
      providers: [
        ScoreSession,
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'session' },
      ],
    });
    session = TestBed.inject(ScoreSession);
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('prepares study atomically without editing or saving the score', async () => {
    session.score.set(score);
    session.transport.set(transport as NonNullable<ReturnType<ScoreSession['transport']>>);
    session.xml.set('<original/>');
    const location = {
      index: 0,
      measureIndex: 0,
      measureNumber: '1',
      eventIndex: 0,
      executionMeasureIndex: 0,
      endMeasureIndex: 0,
    };
    fetcher.mockResolvedValueOnce(
      Response.json({ transport: { ...transport, startingBpm: 60 }, location }),
    );
    const request = {
      sha256: score.sha256,
      scoreXmlSha256: 'c'.repeat(64),
      revision: 0,
      partId: 'P1',
      soundingSemitones: 0,
      expectedIndex: 0,
      bpm: 60,
      repeat: true,
    };
    expect(await session.prepareStudy(request)).toBe(true);
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/score/study');
    expect(JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body))).toEqual(request);
    expect(session.studySelection()).toEqual(location);
    expect(session.score()).toBe(score);
    expect(session.xml()).toBe('<original/>');
    expect(session.transport()?.status).toBe('STOPPED');
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('rejects stale or failed study preparations and keeps the workspace available', async () => {
    session.score.set(score);
    const request = {
      sha256: score.sha256,
      scoreXmlSha256: 'c'.repeat(64),
      revision: 1,
      partId: 'P1',
      soundingSemitones: 0,
      expectedIndex: 0,
      bpm: 60,
      repeat: true,
    };
    expect(await session.prepareStudy(request)).toBe(false);
    expect(fetcher).not.toHaveBeenCalled();
    fetcher.mockResolvedValueOnce(Response.json({ error: 'O conteúdo mudou.' }, { status: 409 }));
    expect(await session.prepareStudy({ ...request, revision: 0 })).toBe(false);
    expect(session.error()).toBe('O conteúdo mudou.');
    expect(session.busy()).toBe(false);
    expect(session.studySelection()).toBeNull();
    expect(session.score()).toBe(score);
  });

  it('sends the original bytes for validation, then controls the canonical transport', async () => {
    fetcher
      .mockResolvedValueOnce(
        Response.json({ xml: '<score-partwise/>', score, transport, preview: null }),
      )
      .mockResolvedValueOnce(Response.json({ ...transport, tick: 1920, measureIndex: 1 }));
    const file = new File(['<score-partwise/>'], 'first.musicxml', { type: 'application/xml' });
    const opened = await session.open(file);
    expect(opened).toBe(true);
    expect(session.score()?.title).toBe('First notes');
    expect(session.xml()).toBe('<score-partwise/>');
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/score/open');
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({
      'X-Arpeon-Session': 'session',
      'Content-Type': 'application/xml',
    });
    expect(fetcher.mock.calls[0]?.[1]?.body).toBe(file);
    await session.action('seek', { tick: 1920 });
    expect(fetcher.mock.calls[1]?.[0]).toBe('/api/transport/seek');
    expect(fetcher.mock.calls[1]?.[1]?.body).toBe('{"tick":1920}');
    expect(session.transport()?.tick).toBe(1920);
  });

  it('routes compressed MuseScore projects to the bounded archive importer', async () => {
    fetcher.mockResolvedValueOnce(
      Response.json({ xml: '<score-partwise/>', score, transport, preview: null }),
    );
    const project = new File(['compressed-score'], 'badinerie.mscz');

    expect(await session.open(project)).toBe(true);
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/score/open-mxl');
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({
      'Content-Type': 'application/octet-stream',
      'X-Arpeon-Filename': 'badinerie.mscz',
    });
    expect(fetcher.mock.calls[0]?.[1]?.body).toBe(project);
  });

  it('applies a validated edit response and exposes its preview before commit', async () => {
    const preview = {
      token: 'review-1',
      description: 'Oitava: 12 semitons',
      changedNotes: 1,
      changes: [{ partId: 'P1', measureIndex: 0, eventIndex: 0, from: 'C4', to: 'C5' }],
      warningsBefore: [],
      warningsAfter: [],
    };
    fetcher
      .mockResolvedValueOnce(Response.json({ xml: '<original/>', score, transport, preview: null }))
      .mockResolvedValueOnce(Response.json({ xml: '<original/>', score, transport, preview }))
      .mockResolvedValueOnce(
        Response.json({
          xml: '<edited/>',
          score: { ...score, revision: 1, canUndo: true },
          transport,
          preview: null,
        }),
      );
    await session.open(new File(['<score-partwise/>'], 'first.musicxml'));
    expect(await session.edit('preview', { kind: 'octave', partId: 'P1', semitones: 12 })).toBe(
      true,
    );
    expect(session.xml()).toBe('<original/>');
    expect(session.preview()?.changedNotes).toBe(1);
    expect(await session.edit('apply-preview', { token: 'review-1' })).toBe(true);
    expect(session.xml()).toBe('<edited/>');
    expect(session.score()?.canUndo).toBe(true);
    expect(fetcher.mock.calls[2]?.[1]?.body).toBe('{"token":"review-1"}');
  });

  it('loads measure events on demand and caches the validated response', async () => {
    const sourcePart = score.parts[0];
    const sourceMeasure = sourcePart?.measures[0];
    const sourceEvent = sourceMeasure?.events[0];
    if (!sourcePart || !sourceMeasure || !sourceEvent) throw new Error('Missing score fixture.');
    const unloaded = {
      ...score,
      parts: [{ ...sourcePart, measures: [{ ...sourceMeasure, events: [] }] }],
    };
    fetcher
      .mockResolvedValueOnce(
        Response.json({ xml: '<score-partwise/>', score: unloaded, transport, preview: null }),
      )
      .mockResolvedValueOnce(
        Response.json({
          revision: 0,
          partId: 'P1',
          index: 0,
          number: '1',
          events: [sourceEvent],
          durationNumerator: 3,
          durationDenominator: 4,
        }),
      );
    await session.open(new File(['<score-partwise/>'], 'first.musicxml'));
    expect(session.score()?.parts[0]?.measures[0]?.events).toEqual([]);
    expect(await session.loadMeasureEvents('P1', 0)).toBe(true);
    expect(await session.loadMeasureEvents('P1', 0)).toBe(true);
    expect(session.score()?.parts[0]?.measures[0]?.events).toEqual([sourceEvent]);
    expect(session.score()?.parts[0]?.measures[0]?.durationNumerator).toBe(3);
    expect(session.score()?.parts[0]?.measures[0]?.durationDenominator).toBe(4);
    expect(fetcher.mock.calls[1]?.[0]).toBe('/api/score/measure-events?partId=P1&measureIndex=0');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('duplicates a saved project without replacing or saving the current dirty score', async () => {
    vi.useFakeTimers();
    const originalId = '123e4567-e89b-42d3-a456-426614174000';
    const copyId = '123e4567-e89b-42d3-a456-426614174001';
    fetcher.mockResolvedValueOnce(
      Response.json({ xml: '<current/>', score, transport, preview: null }),
    );
    await session.acceptImport('draft', 4, 120);
    session.projectId.set(originalId);
    const current = session.score();
    fetcher
      .mockResolvedValueOnce(Response.json({ id: copyId, updatedAt: '2026-10-11T00:00:00Z' }))
      .mockResolvedValueOnce(
        Response.json([
          {
            id: copyId,
            title: 'Estudo lento',
            filename: 'study.musicxml',
            updatedAt: '2026-10-11T00:00:00Z',
          },
        ]),
      );
    expect(await session.manageProject(originalId, 'duplicate', ' Estudo lento ')).toBe(copyId);
    expect(fetcher.mock.calls[1]?.[0]).toBe(`/api/projects/${originalId}/duplicate`);
    expect(fetcher.mock.calls[1]?.[1]?.body).toBe('{"name":"Estudo lento"}');
    expect(session.score()).toBe(current);
    expect(session.xml()).toBe('<current/>');
    expect(session.projectId()).toBe(originalId);
    expect(session.projectDirty()).toBe(true);
    expect(session.savedRevision()).toBeNull();
  });

  it('rejects a mismatched rename response and keeps library and session unchanged', async () => {
    const originalId = '123e4567-e89b-42d3-a456-426614174000';
    fetcher.mockResolvedValueOnce(
      Response.json({
        id: '123e4567-e89b-42d3-a456-426614174001',
        updatedAt: '2026-10-11T00:00:00Z',
      }),
    );
    expect(await session.manageProject(originalId, 'rename', 'Study')).toBeNull();
    expect(session.projects()).toEqual([]);
    expect(session.error()).toContain('Resposta da biblioteca inválida');
    expect(session.busy()).toBe(false);
  });

  it('autosaves an accepted import as a local project after the debounce', async () => {
    vi.useFakeTimers();
    fetcher
      .mockResolvedValueOnce(
        Response.json({ xml: '<score-partwise/>', score, transport, preview: null }),
      )
      .mockResolvedValueOnce(
        Response.json({
          id: '123e4567-e89b-42d3-a456-426614174000',
          updatedAt: '2026-10-09T00:00:00Z',
        }),
      )
      .mockResolvedValueOnce(Response.json([]));

    expect(await session.acceptImport('draft-1', 4, 120)).toBe(true);
    expect(session.projectDirty()).toBe(true);
    await vi.advanceTimersByTimeAsync(900);

    expect(fetcher.mock.calls[1]?.[0]).toBe('/api/projects');
    expect(fetcher.mock.calls[1]?.[1]?.method).toBe('POST');
    expect(fetcher.mock.calls[1]?.[1]?.body).toBe('{"autosave":true}');
    expect(session.projectId()).toBe('123e4567-e89b-42d3-a456-426614174000');
    expect(session.projectDirty()).toBe(false);
    vi.useRealTimers();
  });

  it('keeps unsaved edits after a failed save and clears the warning after retry', async () => {
    vi.useFakeTimers();
    fetcher.mockResolvedValueOnce(
      Response.json({ xml: '<score-partwise/>', score, transport, preview: null }),
    );
    await session.acceptImport('draft', 4, 120);
    const current = session.score();
    fetcher.mockResolvedValueOnce(Response.json({ error: 'Disk unavailable' }, { status: 500 }));
    expect(await session.saveProject()).toBe(false);
    expect(session.score()).toBe(current);
    expect(session.projectDirty()).toBe(true);
    expect(session.savedRevision()).toBeNull();
    expect(session.projectSaveError()).toContain('alterações continuam abertas');
    fetcher
      .mockResolvedValueOnce(
        Response.json({
          id: '123e4567-e89b-42d3-a456-426614174000',
          updatedAt: '2026-10-10T00:00:00Z',
        }),
      )
      .mockResolvedValueOnce(Response.json([]));
    expect(await session.saveProject()).toBe(true);
    expect(session.projectDirty()).toBe(false);
    expect(session.projectSaveError()).toBe('');
    expect(session.busy()).toBe(false);
  });

  it('reconnects playback without replacing the score or marking unsaved edits as saved', async () => {
    vi.useFakeTimers();
    fetcher.mockResolvedValueOnce(
      Response.json({ xml: '<score-partwise/>', score, transport, preview: null }),
    );
    await session.acceptImport('draft', 4, 120);
    const current = session.score();
    session.transport.set(null);
    fetcher.mockResolvedValueOnce(Response.json({ ...transport, tick: 1920 }));
    await session.refreshTransport();
    expect(fetcher.mock.lastCall?.[0]).toBe('/api/transport');
    expect(session.transport()?.tick).toBe(1920);
    expect(session.score()).toBe(current);
    expect(session.savedRevision()).toBeNull();
    expect(session.projectDirty()).toBe(true);
  });

  it('rejects malformed loop boundaries without replacing the current transport', async () => {
    vi.useFakeTimers();
    fetcher.mockResolvedValueOnce(
      Response.json({ xml: '<score-partwise/>', score, transport, preview: null }),
    );
    await session.acceptImport('draft', 4, 120);
    fetcher.mockResolvedValueOnce(
      Response.json({
        ...transport,
        loop: {
          startTick: 1920,
          endTick: 9999,
          startMeasureIndex: 1,
          endMeasureIndex: 1,
        },
      }),
    );
    await session.action('loop', { startMeasureIndex: 1, endMeasureIndex: 1, enabled: true });
    expect(session.transport()).toEqual(transport);
    expect(session.error()).toContain('inválida');
  });

  it('rejects oversized and malformed server responses without replacing the score', async () => {
    const oversized = new File(['x'], 'large.musicxml');
    Object.defineProperty(oversized, 'size', { value: 128 * 1_048_576 + 1 });
    expect(await session.open(oversized)).toBe(false);
    expect(fetcher).not.toHaveBeenCalled();
    fetcher.mockResolvedValue(Response.json({ score, transport: { status: 'PLAYING' } }));
    expect(await session.open(new File(['<score-partwise/>'], 'score.musicxml'))).toBe(false);
    expect(session.score()).toBeNull();
    expect(session.error()).toContain('inválida');
  });
  it('autosaves mixer preferences even when the notation revision does not change', async () => {
    vi.useFakeTimers();
    const id = '12345678-1234-4123-8123-123456789abc';
    session.score.set(score);
    session.transport.set(transport);
    session.projectId.set(id);
    session.savedRevision.set(score.revision);
    expect(session.projectDirty()).toBe(false);
    fetcher.mockResolvedValueOnce(Response.json({ ...transport, master: 0.5 }));
    await session.action('master', { value: 0.5 });
    expect(session.score()?.revision).toBe(score.revision);
    expect(session.projectDirty()).toBe(true);
    fetcher.mockResolvedValueOnce(Response.json({ id, updatedAt: '2026-10-18T12:00:00Z' }));
    fetcher.mockResolvedValueOnce(Response.json([]));
    expect(await session.saveProject()).toBe(true);
    expect(session.projectDirty()).toBe(false);
  });
});
