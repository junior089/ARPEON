import { Component, computed, input, linkedSignal, output, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Report, StudyContext, StudyLocation } from './analysis-contract';

export interface StudyRequest {
  expectedIndex: number;
  bpm: number;
  repeat: boolean;
}
export interface StudyPassage {
  location: StudyLocation;
  expected: number;
  affected: number;
  pitch: number;
  early: number;
  late: number;
  duration: number;
  missed: number;
}
export function noteName(midi: number): string {
  const names = ['Dó', 'Dó♯', 'Ré', 'Mi♭', 'Mi', 'Fá', 'Fá♯', 'Sol', 'Lá♭', 'Lá', 'Si♭', 'Si'];
  return `${names[((midi % 12) + 12) % 12]} ${Math.floor(midi / 12) - 1}`;
}

export function studyPassages(report: Report, context: StudyContext | null): StudyPassage[] {
  if (report.status !== 'ANALYZED' || !context || context.notes.length !== report.expectedCount)
    return [];
  const groups = new Map<number, StudyPassage>();
  for (const location of context.notes) {
    let group = groups.get(location.executionMeasureIndex);
    if (!group) {
      group = {
        location,
        expected: 0,
        affected: 0,
        pitch: 0,
        early: 0,
        late: 0,
        duration: 0,
        missed: 0,
      };
      groups.set(location.executionMeasureIndex, group);
    }
    group.expected++;
  }
  for (const match of report.matches) {
    const location = context.notes[match.expectedIndex];
    const group = location && groups.get(location.executionMeasureIndex);
    // The UI uses a disclosed caution threshold; analyzer metrics remain unchanged.
    if (!group || match.confidence < 0.8) continue;
    if (!match.pitchWithinTolerance) group.pitch++;
    if (!match.timingWithinTolerance) {
      if (match.onsetErrorMs < 0) group.early++;
      else group.late++;
    }
    if (!match.durationWithinTolerance) group.duration++;
    if (
      !match.pitchWithinTolerance ||
      !match.timingWithinTolerance ||
      !match.durationWithinTolerance
    )
      group.affected++;
  }
  for (const index of new Set(report.missedIndices)) {
    const location = context.notes[index];
    const group = location && groups.get(location.executionMeasureIndex);
    if (group) {
      group.missed++;
      group.affected++;
    }
  }
  return [...groups.values()]
    .filter((group) => group.affected > 0)
    .sort(
      (a, b) =>
        b.affected / b.expected - a.affected / a.expected ||
        b.affected - a.affected ||
        a.location.executionMeasureIndex - b.location.executionMeasureIndex,
    )
    .slice(0, 3);
}

@Component({
  selector: 'app-study-report',
  imports: [DecimalPipe],
  templateUrl: './study-report.html',
  styleUrl: './study-report.scss',
})
export class StudyReport {
  readonly report = input.required<Report>();
  readonly reportKey = input.required<string | number>();
  readonly context = input<StudyContext | null>(null);
  readonly compatible = input(false);
  readonly busy = input(false);
  readonly prepared = output<StudyRequest>();
  readonly bpmOverride = linkedSignal<string | number, number | null>({
    source: this.reportKey,
    computation: () => null,
  });
  readonly bpm = computed(
    () =>
      this.bpmOverride() ??
      Math.max(30, Math.min(300, Math.round((this.context()?.initialBpm ?? 120) * 0.8))),
  );
  readonly validBpm = computed(
    () => Number.isFinite(this.bpm()) && this.bpm() >= 30 && this.bpm() <= 300,
  );
  readonly passages = computed(() => studyPassages(this.report(), this.context()));
  readonly page = signal(0);
  readonly pageCount = computed(() => Math.max(1, Math.ceil(this.report().matches.length / 48)));
  readonly currentPage = computed(() => Math.min(this.page(), this.pageCount() - 1));
  readonly matches = computed(() =>
    this.report().matches.slice(this.currentPage() * 48, (this.currentPage() + 1) * 48),
  );
  readonly coverage = computed(() =>
    this.report().expectedCount
      ? (100 * this.report().matchedCount) / this.report().expectedCount
      : 0,
  );
  readonly caution = computed(() => (this.report().meanMatchedConfidence ?? 0) < 0.8);
  readonly noteName = noteName;
  readonly indexLabel = (index: number) => index + 1;
  setBpm(event: Event): void {
    if (event.target instanceof HTMLInputElement) this.bpmOverride.set(event.target.valueAsNumber);
  }
  prepare(index: number, repeat: boolean): void {
    if (
      this.busy() ||
      !this.compatible() ||
      (repeat && !this.validBpm()) ||
      !this.context()?.notes[index]
    )
      return;
    this.prepared.emit({ expectedIndex: index, bpm: this.validBpm() ? this.bpm() : 120, repeat });
  }
}
