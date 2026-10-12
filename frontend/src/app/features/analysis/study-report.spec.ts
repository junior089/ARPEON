import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Report, StudyContext, isReport, isStudyContext } from './analysis-contract';
import { StudyReport, noteName, studyPassages } from './study-report';

const context = (): StudyContext => ({
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
  notes: Array.from({ length: 8 }, (_, index) => ({
    index,
    measureIndex: 0,
    measureNumber: 'A',
    eventIndex: index % 4,
    executionMeasureIndex: Math.floor(index / 4),
    endMeasureIndex: Math.floor(index / 4),
  })),
});
const report = (): Report => ({
  status: 'ANALYZED',
  expectedCount: 8,
  observedCount: 7,
  matchedCount: 6,
  missedCount: 2,
  extraCount: 1,
  earlyCount: 1,
  lateCount: 1,
  pitchAccuracyPercent: 83.3,
  timingAccuracyPercent: 66.7,
  durationAccuracyPercent: 83.3,
  interOnsetAccuracyPercent: 60,
  meanAbsoluteTimingErrorMs: 60,
  meanAbsoluteIntonationCents: 20,
  meanMatchedConfidence: 0.94,
  matches: Array.from({ length: 6 }, (_, i) => ({
    expectedIndex: i,
    observedIndex: i,
    expectedMidi: 60 + i,
    observedMidi: 60 + i,
    onsetErrorMs: i === 0 ? -160 : i === 4 ? 180 : 0,
    durationErrorPercent: i === 4 ? 35 : 0,
    centsError: i === 0 ? 80 : 0,
    confidence: 0.94,
    pitchWithinTolerance: i !== 0,
    timingWithinTolerance: i !== 0 && i !== 4,
    durationWithinTolerance: i !== 4,
  })),
  missedIndices: [6, 7],
  extraIndices: [6],
});
async function render(
  result = report(),
  study: StudyContext | null = context(),
  compatible = true,
) {
  await TestBed.configureTestingModule({ imports: [StudyReport] }).compileComponents();
  const fixture = TestBed.createComponent(StudyReport);
  fixture.componentRef.setInput('report', result);
  fixture.componentRef.setInput('reportKey', 'report-a');
  fixture.componentRef.setInput('context', study);
  fixture.componentRef.setInput('compatible', compatible);
  fixture.detectChanges();
  return {
    fixture,
    component: fixture.componentInstance,
    root: fixture.nativeElement as HTMLElement,
  };
}
describe('StudyReport', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('separates repeated passages, counts affected notes once and ranks their proportion', () => {
    const groups = studyPassages(report(), context());
    expect(groups.map((g) => g.location.executionMeasureIndex)).toEqual([1, 0]);
    expect(groups[0]).toMatchObject({ affected: 3, expected: 4, late: 1, duration: 1, missed: 2 });
    expect(groups[1]).toMatchObject({ affected: 1, pitch: 1, early: 1 });
  });
  it('retains misses but excludes low-confidence musical advice without altering metrics', () => {
    const data = report();
    data.matches.forEach((match) => (match.confidence = 0.7));
    const before = structuredClone(data);
    const groups = studyPassages(data, context());
    expect(groups).toHaveLength(1);
    expect(groups[0]).toMatchObject({ affected: 2, missed: 2, pitch: 0, late: 0 });
    expect(data).toEqual(before);
    expect(studyPassages({ ...data, status: 'INSUFFICIENT_DATA' }, context())).toEqual([]);
  });
  it('shows coverage, musical names and adjustable preparation without starting playback', async () => {
    const { root, component, fixture } = await render();
    const prepare = vi.fn();
    component.prepared.subscribe(prepare);
    expect(root.textContent).toContain('6 de 8 notas correspondidas');
    expect(root.textContent).toContain('75%');
    expect(root.textContent).toContain('Dó 4');
    expect(noteName(69)).toBe('Lá 4');
    expect(noteName(0)).toBe('Dó -1');
    const input = root.querySelector<HTMLInputElement>('input');
    if (!input) throw new Error('Missing tempo');
    expect(input.value).toBe('96');
    input.value = '60';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('.passages button')?.click();
    expect(prepare).toHaveBeenCalledWith({ expectedIndex: 4, bpm: 60, repeat: true });
    root.querySelector<HTMLButtonElement>('tbody button')?.click();
    expect(prepare).toHaveBeenLastCalledWith({ expectedIndex: 0, bpm: 60, repeat: false });
    input.value = '';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('.passages button')?.disabled).toBe(true);
  });
  it('disables preparation for incompatible revisions and retains old reports', async () => {
    const { root, component, fixture } = await render(report(), context(), false);
    expect(root.textContent).toContain('a revisão ou o instrumento mudou');
    expect(root.querySelector<HTMLButtonElement>('.passages button')?.disabled).toBe(true);
    component.prepare(0, true);
    fixture.componentRef.setInput('context', null);
    fixture.detectChanges();
    expect(root.textContent).toContain('relatório antigo');
    expect(root.textContent).toContain('Dó 4');
    expect(root.querySelectorAll('.passages button')).toHaveLength(0);
  });
  it.each(['60', ''])('resets the BPM draft %j when switching reports', async (draft) => {
    const { root, component, fixture } = await render();
    const input = root.querySelector<HTMLInputElement>('input');
    if (!input) throw new Error('Missing tempo');
    input.value = draft;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    fixture.componentRef.setInput('report', structuredClone(report()));
    fixture.componentRef.setInput('context', structuredClone(context()));
    fixture.detectChanges();
    expect(input.value).toBe(draft);
    const nextContext = { ...context(), initialBpm: 90 };
    fixture.componentRef.setInput('reportKey', 'report-b');
    fixture.componentRef.setInput('report', structuredClone(report()));
    fixture.componentRef.setInput('context', nextContext);
    fixture.detectChanges();
    expect(input.value).toBe('72');
    expect(component.validBpm()).toBe(true);
    expect(root.querySelector<HTMLButtonElement>('.passages button')?.disabled).toBe(false);
  });
  it('prioritizes capture caution, null metrics and insufficient data', async () => {
    const data = report();
    data.meanMatchedConfidence = 0.7;
    data.matchedCount = 0;
    data.matches = [];
    data.pitchAccuracyPercent = null;
    data.timingAccuracyPercent = null;
    const { root, fixture } = await render(data);
    expect(root.textContent).toContain('cobertura ficou abaixo de 70%');
    expect(root.textContent).toContain('captura pede atenção');
    expect(root.querySelector('.metrics')?.textContent).toContain('—');
    fixture.componentRef.setInput('report', { ...data, status: 'INSUFFICIENT_DATA' });
    fixture.detectChanges();
    expect(root.textContent).toContain('Nenhuma nota estável');
    expect(root.querySelector('.metrics')).toBeNull();
    expect(root.querySelector('.passages')).toBeNull();
  });
  it('paginates matched notes with at most 48 rows', async () => {
    const data = report();
    const first = report().matches[0];
    if (!first) throw new Error('Missing match');
    data.matches = Array.from({ length: 52 }, (_, i) => ({
      ...first,
      expectedIndex: i,
      observedIndex: i,
    }));
    data.expectedCount = 52;
    data.observedCount = 52;
    data.matchedCount = 52;
    const { root, component, fixture } = await render(data, null);
    expect(root.querySelectorAll('tbody tr')).toHaveLength(48);
    component.page.set(1);
    fixture.detectChanges();
    expect(root.querySelectorAll('tbody tr')).toHaveLength(4);
    fixture.componentRef.setInput('report', report());
    fixture.detectChanges();
    expect(component.currentPage()).toBe(0);
  });
  it('validates study metadata and rejects malformed locations', () => {
    expect(isReport(report())).toBe(true);
    expect(isStudyContext(context())).toBe(true);
    const bad = context();
    const location = bad.notes[0];
    if (!location) throw new Error('Missing location');
    location.index = 99;
    expect(isStudyContext(bad)).toBe(false);
    expect(isStudyContext({ ...context(), scoreXmlSha256: 'bad' })).toBe(false);
    expect(isStudyContext({ ...context(), initialBpm: NaN })).toBe(false);
  });
});
