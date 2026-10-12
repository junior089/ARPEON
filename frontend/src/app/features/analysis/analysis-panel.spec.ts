import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { ScoreSession } from '../score/score-session';
import { AnalysisPanel } from './analysis-panel';
import { SavedReport } from './analysis-contract';

const saved = (): SavedReport => ({
  id: 'saved',
  projectId: null,
  sha256: 'a'.repeat(64),
  revision: 0,
  title: 'Study',
  filename: 'study.musicxml',
  partId: 'P1',
  partName: 'Flute',
  mode: 'ANALYSIS',
  completedAt: '2026-10-11T03:00:00Z',
  reportStatus: 'ANALYZED',
  expectedCount: 1,
  matchedCount: 1,
  missedCount: 0,
  extraCount: 0,
  pitchAccuracyPercent: 100,
  timingAccuracyPercent: 100,
  durationAccuracyPercent: 100,
  interOnsetAccuracyPercent: null,
  report: {
    status: 'ANALYZED',
    expectedCount: 1,
    observedCount: 1,
    matchedCount: 1,
    missedCount: 0,
    extraCount: 0,
    earlyCount: 0,
    lateCount: 0,
    pitchAccuracyPercent: 100,
    timingAccuracyPercent: 100,
    durationAccuracyPercent: 100,
    interOnsetAccuracyPercent: null,
    meanAbsoluteTimingErrorMs: 0,
    meanAbsoluteIntonationCents: 0,
    meanMatchedConfidence: 0.95,
    matches: [],
    missedIndices: [],
    extraIndices: [],
  },
  study: {
    scoreXmlSha256: 'c'.repeat(64),
    initialBpm: 120,
    soundingSemitones: 0,
    config: {
      alignmentWindowMs: 450,
      timingToleranceMs: 120,
      pitchToleranceCents: 50,
      rhythmTolerancePercent: 20,
      latencyCompensationMs: 0,
    },
    notes: [
      {
        index: 0,
        measureIndex: 0,
        measureNumber: '1',
        eventIndex: 0,
        executionMeasureIndex: 0,
        endMeasureIndex: 0,
      },
    ],
  },
});
async function panel() {
  const session = {
    score: signal({
      sha256: 'a'.repeat(64),
      revision: 0,
      parts: [{ id: 'P1' }],
      instruments: [{ id: 'flute', soundingSemitones: 0 }],
    }),
    transport: signal({ parts: [{ id: 'P1', instrumentId: 'flute' }] }),
    xml: signal(''),
    busy: signal(false),
    error: signal(''),
    prepareStudy: vi.fn().mockResolvedValue(true),
  };
  const fetcher = vi
    .fn<typeof fetch>()
    .mockImplementation(async (input) =>
      input === '/api/analysis'
        ? Response.json({ status: 'IDLE', message: '', mode: 'ANALYSIS' })
        : Response.json(input === '/api/audio/preferences' ? null : []),
    );
  await TestBed.configureTestingModule({
    imports: [AnalysisPanel],
    providers: [
      { provide: ScoreSession, useValue: session },
      { provide: LOCAL_FETCH, useValue: fetcher },
      { provide: SESSION_TOKEN, useValue: 'session' },
    ],
  })
    .overrideComponent(AnalysisPanel, { set: { template: '' } })
    .compileComponents();
  const fixture = TestBed.createComponent(AnalysisPanel);
  fixture.detectChanges();
  await fixture.whenStable();
  return { fixture, component: fixture.componentInstance, session, fetcher };
}
describe('AnalysisPanel study flow', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.clear();
  });
  it('opens study mode only after successful preparation with the saved report identity', async () => {
    const { component, session } = await panel();
    component.savedReport.set(saved());
    const navigate = vi.fn();
    component.requestedScore.subscribe(navigate);
    expect(component.compatible(saved())).toBe(true);
    await component.prepareStudy({ expectedIndex: 0, bpm: 72, repeat: true }, true);
    expect(session.prepareStudy).toHaveBeenCalledWith({
      sha256: 'a'.repeat(64),
      scoreXmlSha256: 'c'.repeat(64),
      revision: 0,
      partId: 'P1',
      soundingSemitones: 0,
      expectedIndex: 0,
      bpm: 72,
      repeat: true,
    });
    expect(navigate).toHaveBeenCalledOnce();
    expect(component.busy()).toBe(false);
  });
  it('keeps failed preparations in the report and checks revision and instrument compatibility', async () => {
    const { component, session } = await panel();
    component.savedReport.set(saved());
    const navigate = vi.fn();
    component.requestedScore.subscribe(navigate);
    session.prepareStudy.mockResolvedValue(false);
    session.error.set('A partitura mudou.');
    await component.prepareStudy({ expectedIndex: 0, bpm: 72, repeat: true }, true);
    expect(navigate).not.toHaveBeenCalled();
    expect(component.error()).toBe('A partitura mudou.');
    expect(component.compatible({ ...saved(), revision: 1 })).toBe(false);
    const transposed = saved();
    if (transposed.study) transposed.study.soundingSemitones = -2;
    expect(component.compatible(transposed)).toBe(false);
    expect(component.compatible({ ...saved(), study: null })).toBe(false);
  });
  it('refuses invalid analysis criteria before requesting microphone capture', async () => {
    const { component, fetcher } = await panel();
    await component.audio.refresh();
    component.audio.devices.set([{ id: 'input', name: 'Input', sampleRatesHz: [44100] }]);
    component.audio.selectedDevice.set('input');
    expect(component.canStart()).toBe(true);
    component.timingToleranceMs.set(451);
    expect(component.canStart()).toBe(false);
    await component.start();
    expect(fetcher.mock.calls.some(([input]) => input === '/api/analysis/start')).toBe(false);
    component.resetAnalysisSettings();
    expect(component.canStart()).toBe(true);
    component.partId.set('missing');
    expect(component.canStart()).toBe(false);
  });
  it('retains advanced edits when the analysis panel is recreated', async () => {
    const { component, fixture } = await panel();
    component.latencyCompensationMs.set(80);
    component.pitchToleranceCents.set(25);
    fixture.destroy();
    const next = TestBed.createComponent(AnalysisPanel);
    expect([
      next.componentInstance.latencyCompensationMs(),
      next.componentInstance.pitchToleranceCents(),
    ]).toEqual([80, 25]);
    next.destroy();
  });
  it('selects a valid part when the score arrives after the panel opens and preserves valid choices', async () => {
    const { component, fixture, session } = await panel();
    const current = session.score();
    session.score.set({ ...current, parts: [] });
    fixture.detectChanges();
    expect(component.partId()).toBe('');
    session.score.set({ ...current, parts: [{ id: 'P2' }, { id: 'P3' }] });
    fixture.detectChanges();
    expect(component.partId()).toBe('P2');
    component.partId.set('P3');
    session.score.set({ ...current, revision: 1, parts: [{ id: 'P2' }, { id: 'P3' }] });
    fixture.detectChanges();
    expect(component.partId()).toBe('P3');
  });
  it('restores analysis defaults while preserving the part, mode, microphone and reference', async () => {
    const { component } = await panel();
    component.mode.set('ANALYSIS');
    component.deviceId.set('selected-input');
    component.referenceHz.set(442.1);
    component.alignmentWindowMs.set(600);
    component.timingToleranceMs.set(200);
    component.pitchToleranceCents.set(25);
    component.rhythmTolerancePercent.set(10);
    component.latencyCompensationMs.set(80);
    component.resetAnalysisSettings();
    expect([
      component.alignmentWindowMs(),
      component.timingToleranceMs(),
      component.pitchToleranceCents(),
      component.rhythmTolerancePercent(),
      component.latencyCompensationMs(),
    ]).toEqual([450, 120, 50, 20, 0]);
    expect([
      component.partId(),
      component.mode(),
      component.deviceId(),
      component.referenceHz(),
    ]).toEqual(['P1', 'ANALYSIS', 'selected-input', 442.1]);
  });
  it('does not change analysis criteria while a start is pending or capture is active', async () => {
    const { component } = await panel();
    component.latencyCompensationMs.set(80);
    component.busy.set(true);
    component.resetAnalysisSettings();
    expect(component.latencyCompensationMs()).toBe(80);
    component.busy.set(false);
    component.state.set({
      status: 'RECORDING',
      message: '',
      mode: 'FOLLOW',
      title: null,
      filename: null,
      sha256: null,
      revision: null,
      partId: 'P1',
      partName: null,
      soundingSemitones: 0,
      expectedCount: 0,
      durationMs: null,
      initialBpm: null,
      startsAtEpochMs: null,
      elapsedMs: 0,
      observedOnsets: 0,
      expected: [],
      expectedTicks: [],
      follow: null,
      report: null,
    });
    component.resetAnalysisSettings();
    expect(component.latencyCompensationMs()).toBe(80);
  });
});
