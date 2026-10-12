export interface Match {
  expectedIndex: number;
  observedIndex: number;
  expectedMidi: number;
  observedMidi: number;
  onsetErrorMs: number;
  durationErrorPercent: number;
  centsError: number;
  confidence: number;
  pitchWithinTolerance: boolean;
  timingWithinTolerance: boolean;
  durationWithinTolerance: boolean;
}
export interface Report {
  status: string;
  expectedCount: number;
  observedCount: number;
  matchedCount: number;
  missedCount: number | null;
  extraCount: number | null;
  earlyCount: number | null;
  lateCount: number | null;
  pitchAccuracyPercent: number | null;
  timingAccuracyPercent: number | null;
  durationAccuracyPercent: number | null;
  interOnsetAccuracyPercent: number | null;
  meanAbsoluteTimingErrorMs: number | null;
  meanAbsoluteIntonationCents: number | null;
  meanMatchedConfidence: number | null;
  matches: Match[];
  missedIndices: number[];
  extraIndices: number[];
}
export interface HistorySummary {
  id: string;
  projectId: string | null;
  sha256: string;
  revision: number;
  title: string;
  filename: string;
  partName: string;
  mode: string;
  completedAt: string;
  reportStatus: string;
  expectedCount: number;
  matchedCount: number;
  missedCount: number | null;
  extraCount: number | null;
  pitchAccuracyPercent: number | null;
  timingAccuracyPercent: number | null;
  durationAccuracyPercent: number | null;
  interOnsetAccuracyPercent: number | null;
}
export interface SavedReport extends HistorySummary {
  partId: string;
  report: Report;
  study?: StudyContext | null;
}
export interface StudyLocation {
  index: number;
  measureIndex: number;
  measureNumber: string;
  eventIndex: number;
  executionMeasureIndex: number;
  endMeasureIndex: number;
}
export interface StudyContext {
  scoreXmlSha256: string;
  initialBpm: number;
  soundingSemitones: number;
  config: {
    alignmentWindowMs: number;
    timingToleranceMs: number;
    pitchToleranceCents: number;
    rhythmTolerancePercent: number;
    latencyCompensationMs: number;
  };
  notes: StudyLocation[];
}
export interface AnalysisView {
  status: string;
  mode: 'ANALYSIS' | 'FOLLOW';
  title: string | null;
  filename: string | null;
  sha256: string | null;
  revision: number | null;
  partId: string | null;
  partName: string | null;
  soundingSemitones: number | null;
  expectedCount: number | null;
  durationMs: number | null;
  initialBpm: number | null;
  startsAtEpochMs: number | null;
  elapsedMs: number | null;
  observedOnsets: number;
  expected: { index: number; midi: number; onsetMs: number; durationMs: number }[] | null;
  expectedTicks: number[] | null;
  follow: {
    noteIndex: number;
    state: string;
    confidence: number;
    confirmedNotes: number;
    tempoRatio: number;
    tempoSamples: number;
    tempoState: string;
  } | null;
  report: Report | null;
  message: string;
  study?: StudyContext | null;
}

export function isHistorySummary(value: unknown): value is HistorySummary {
  return (
    isRecord(value) &&
    'id' in value &&
    typeof value['id'] === 'string' &&
    'projectId' in value &&
    (value['projectId'] === null || typeof value['projectId'] === 'string') &&
    'sha256' in value &&
    typeof value['sha256'] === 'string' &&
    'revision' in value &&
    typeof value['revision'] === 'number' &&
    typeof value['title'] === 'string' &&
    typeof value['filename'] === 'string' &&
    typeof value['partName'] === 'string' &&
    typeof value['mode'] === 'string' &&
    typeof value['completedAt'] === 'string' &&
    'reportStatus' in value &&
    typeof value['reportStatus'] === 'string' &&
    typeof value['expectedCount'] === 'number' &&
    typeof value['matchedCount'] === 'number' &&
    nullableNumber(value['missedCount']) &&
    nullableNumber(value['extraCount']) &&
    nullableNumber(value['pitchAccuracyPercent']) &&
    nullableNumber(value['timingAccuracyPercent']) &&
    nullableNumber(value['durationAccuracyPercent']) &&
    nullableNumber(value['interOnsetAccuracyPercent'])
  );
}

export function isSavedReport(value: unknown): value is SavedReport {
  return (
    isRecord(value) &&
    typeof value['id'] === 'string' &&
    (value['projectId'] === null || typeof value['projectId'] === 'string') &&
    typeof value['sha256'] === 'string' &&
    typeof value['revision'] === 'number' &&
    typeof value['title'] === 'string' &&
    typeof value['filename'] === 'string' &&
    typeof value['partId'] === 'string' &&
    typeof value['partName'] === 'string' &&
    typeof value['mode'] === 'string' &&
    typeof value['completedAt'] === 'string' &&
    isReport(value['report']) &&
    (value['study'] === undefined ||
      value['study'] === null ||
      (isStudyContext(value['study']) &&
        value['study'].notes.length === value['report'].expectedCount))
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isReport(value: unknown): value is Report {
  return (
    isRecord(value) &&
    ['ANALYZED', 'INSUFFICIENT_DATA'].includes(String(value['status'])) &&
    count(value['expectedCount'], 2048) &&
    value['expectedCount'] > 0 &&
    count(value['observedCount'], 4096) &&
    count(value['matchedCount'], value['expectedCount']) &&
    nullableNumber(value['missedCount']) &&
    nullableNumber(value['extraCount']) &&
    nullableNumber(value['earlyCount']) &&
    nullableNumber(value['lateCount']) &&
    nullableNumber(value['pitchAccuracyPercent']) &&
    nullableNumber(value['timingAccuracyPercent']) &&
    nullableNumber(value['durationAccuracyPercent']) &&
    nullableNumber(value['interOnsetAccuracyPercent']) &&
    nullableNumber(value['meanAbsoluteTimingErrorMs']) &&
    nullableNumber(value['meanAbsoluteIntonationCents']) &&
    nullableNumber(value['meanMatchedConfidence']) &&
    Array.isArray(value['matches']) &&
    value['matches'].length <= 2048 &&
    value['matches'].every(isMatch) &&
    Array.isArray(value['missedIndices']) &&
    value['missedIndices'].length <= 2048 &&
    value['missedIndices'].every((index) => count(index, (value['expectedCount'] as number) - 1)) &&
    Array.isArray(value['extraIndices']) &&
    value['extraIndices'].length <= 4096 &&
    value['extraIndices'].every((index) => count(index, (value['observedCount'] as number) - 1))
  );
}

export function isStudyContext(value: unknown): value is StudyContext {
  if (
    !isRecord(value) ||
    typeof value['scoreXmlSha256'] !== 'string' ||
    !/^[0-9a-f]{64}$/.test(value['scoreXmlSha256']) ||
    typeof value['initialBpm'] !== 'number' ||
    !Number.isFinite(value['initialBpm']) ||
    value['initialBpm'] <= 0 ||
    !Number.isInteger(value['soundingSemitones']) ||
    !isRecord(value['config']) ||
    !Array.isArray(value['notes']) ||
    value['notes'].length > 2048
  )
    return false;
  const c = value['config'];
  return (
    [
      'alignmentWindowMs',
      'timingToleranceMs',
      'pitchToleranceCents',
      'rhythmTolerancePercent',
      'latencyCompensationMs',
    ].every((key) => Number.isInteger(c[key])) &&
    value['notes'].every((note, index) => isStudyLocation(note) && note.index === index)
  );
}

export function isStudyLocation(value: unknown): value is StudyLocation {
  return (
    isRecord(value) &&
    typeof value['measureNumber'] === 'string' &&
    ['index', 'measureIndex', 'eventIndex', 'executionMeasureIndex', 'endMeasureIndex'].every(
      (key) => Number.isSafeInteger(value[key]) && (value[key] as number) >= 0,
    ) &&
    (value['endMeasureIndex'] as number) >= (value['executionMeasureIndex'] as number)
  );
}

function nullableNumber(value: unknown): value is number | null {
  return value === null || (typeof value === 'number' && Number.isFinite(value));
}

function count(value: unknown, max: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= max;
}

function isMatch(value: unknown): value is Match {
  return (
    isRecord(value) &&
    ['expectedIndex', 'observedIndex', 'expectedMidi', 'observedMidi'].every((field) =>
      count(value[field], field.endsWith('Midi') ? 127 : field === 'expectedIndex' ? 2047 : 4095),
    ) &&
    (value['confidence'] as number) >= 0 &&
    (value['confidence'] as number) <= 1 &&
    ['onsetErrorMs', 'durationErrorPercent', 'centsError', 'confidence'].every(
      (field) => typeof value[field] === 'number' && Number.isFinite(value[field]),
    ) &&
    ['pitchWithinTolerance', 'timingWithinTolerance', 'durationWithinTolerance'].every(
      (field) => typeof value[field] === 'boolean',
    )
  );
}
