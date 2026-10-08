import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { ScoreSession } from './score-session';

describe('ScoreSession', () => {
  const fetcher = vi.fn<typeof fetch>();
  const transport = {
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
  const score = {
    title: 'First notes',
    filename: 'first.musicxml',
    sha256: 'a'.repeat(64),
    parserVersion: 'musicxml-m0-v1',
    sourceKind: 'MUSICXML',
    importWarnings: [],
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
  afterEach(() => TestBed.resetTestingModule());

  it('sends the original bytes for validation, then controls the canonical transport', async () => {
    fetcher
      .mockResolvedValueOnce(
        Response.json({ xml: '<score-partwise/>', score, transport, preview: null }),
      )
      .mockResolvedValueOnce(Response.json({ ...transport, tick: 1920, measureIndex: 1 }));
    const opened = await session.open(
      new File(['<score-partwise/>'], 'first.musicxml', { type: 'application/xml' }),
    );
    expect(opened).toBe(true);
    expect(session.score()?.title).toBe('First notes');
    expect(session.xml()).toBe('<score-partwise/>');
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/score/open');
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({
      'X-Arpeon-Session': 'session',
      'Content-Type': 'application/xml',
    });
    expect(fetcher.mock.calls[0]?.[1]?.body).toBeInstanceOf(ArrayBuffer);
    await session.action('seek', { tick: 1920 });
    expect(fetcher.mock.calls[1]?.[0]).toBe('/api/transport/seek');
    expect(fetcher.mock.calls[1]?.[1]?.body).toBe('{"tick":1920}');
    expect(session.transport()?.tick).toBe(1920);
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

  it('rejects oversized and malformed server responses without replacing the score', async () => {
    expect(await session.open(new File(['x'.repeat(1_048_577)], 'large.musicxml'))).toBe(false);
    expect(fetcher).not.toHaveBeenCalled();
    fetcher.mockResolvedValue(Response.json({ score, transport: { status: 'PLAYING' } }));
    expect(await session.open(new File(['<score-partwise/>'], 'score.musicxml'))).toBe(false);
    expect(session.score()).toBeNull();
    expect(session.error()).toContain('inválida');
  });
});
