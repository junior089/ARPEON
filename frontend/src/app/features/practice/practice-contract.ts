import { isObservation, TunerObservation } from '../../core/tuner-contract';

export interface PracticeTarget {
  pitch: string;
  midi: number;
  startTick: number;
  durationTicks: number;
}

export interface PracticeFeedback {
  state: string;
  target: string;
  detected: string | null;
  cents: number | null;
  confidence: number;
  responseMs: number | null;
  heldMs: number | null;
  stabilityCents: number | null;
  interruptions: number;
  timingErrorMs: number | null;
}

export interface PracticeView {
  kind: string;
  scaleType: string;
  keyId: string;
  octave: number;
  bpm: number;
  longToneTargetMs: number;
  iteration: number;
  loop: boolean;
  progressiveStep: number;
  fingeringDatasetId: string;
  xml: string;
  targets: PracticeTarget[];
  targetIndex: number;
  active: boolean;
  attemptOpen: boolean;
  feedback: PracticeFeedback;
  transport: { status: string; tick: number; totalTicks: number };
}

export interface PracticeSummary {
  id: string;
  kind: string;
  keyId: string;
  startedAt: string;
  endedAt: string;
  completed: boolean;
  targets: number;
  reached: number;
  heldMs: number;
  stabilityCents: number | null;
  interruptions: number;
  bpm: number;
}

export interface PracticeHistoryPage {
  entries: PracticeSummary[];
  page: number;
  pageSize: number;
  matchingCount: number;
  totalCount: number;
  completedCount: number;
}

export interface PracticeObservation {
  practice: PracticeView;
  observation: TunerObservation;
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function finite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function optionalNumber(value: unknown): value is number | null {
  return value === null || finite(value);
}

export function isPractice(value: unknown): value is PracticeView {
  if (!record(value) || !record(value['feedback']) || !record(value['transport'])) return false;
  return (
    typeof value['kind'] === 'string' &&
    typeof value['keyId'] === 'string' &&
    typeof value['scaleType'] === 'string' &&
    typeof value['xml'] === 'string' &&
    finite(value['octave']) &&
    finite(value['bpm']) &&
    finite(value['longToneTargetMs']) &&
    finite(value['iteration']) &&
    typeof value['loop'] === 'boolean' &&
    finite(value['progressiveStep']) &&
    typeof value['fingeringDatasetId'] === 'string' &&
    Array.isArray(value['targets']) &&
    value['targets'].every(
      (target: unknown) =>
        record(target) &&
        typeof target['pitch'] === 'string' &&
        finite(target['midi']) &&
        finite(target['startTick']) &&
        finite(target['durationTicks']),
    ) &&
    finite(value['targetIndex']) &&
    typeof value['active'] === 'boolean' &&
    typeof value['attemptOpen'] === 'boolean' &&
    typeof value['feedback']['state'] === 'string' &&
    typeof value['feedback']['target'] === 'string' &&
    (value['feedback']['detected'] === null || typeof value['feedback']['detected'] === 'string') &&
    optionalNumber(value['feedback']['cents']) &&
    finite(value['feedback']['confidence']) &&
    value['feedback']['confidence'] >= 0 &&
    value['feedback']['confidence'] <= 1 &&
    optionalNumber(value['feedback']['responseMs']) &&
    optionalNumber(value['feedback']['heldMs']) &&
    optionalNumber(value['feedback']['stabilityCents']) &&
    finite(value['feedback']['interruptions']) &&
    optionalNumber(value['feedback']['timingErrorMs']) &&
    typeof value['transport']['status'] === 'string' &&
    finite(value['transport']['tick']) &&
    finite(value['transport']['totalTicks'])
  );
}

export function isPracticeObservation(value: unknown): value is PracticeObservation {
  return record(value) && isPractice(value['practice']) && isObservation(value['observation']);
}

export function isPracticeHistory(value: unknown): value is PracticeSummary[] {
  return Array.isArray(value) && value.every(isPracticeSummary);
}

export function isPracticeSummary(item: unknown): item is PracticeSummary {
  return (
    record(item) &&
    typeof item['id'] === 'string' &&
    typeof item['kind'] === 'string' &&
    typeof item['keyId'] === 'string' &&
    typeof item['startedAt'] === 'string' &&
    typeof item['endedAt'] === 'string' &&
    typeof item['completed'] === 'boolean' &&
    finite(item['targets']) &&
    finite(item['reached']) &&
    finite(item['heldMs']) &&
    optionalNumber(item['stabilityCents']) &&
    finite(item['interruptions']) &&
    finite(item['bpm'])
  );
}

export function isPracticeHistoryPage(value: unknown): value is PracticeHistoryPage {
  if (!record(value) || !isPracticeHistory(value['entries'])) return false;
  for (const key of ['page', 'pageSize', 'matchingCount', 'totalCount', 'completedCount'])
    if (!Number.isSafeInteger(value[key]) || (value[key] as number) < 0) return false;
  const page = value as unknown as PracticeHistoryPage;
  return (
    page.pageSize >= 1 &&
    page.pageSize <= 50 &&
    page.totalCount <= 5000 &&
    page.matchingCount <= page.totalCount &&
    page.completedCount <= page.matchingCount &&
    page.page <= Math.max(0, Math.ceil(page.matchingCount / page.pageSize) - 1) &&
    page.entries.length ===
      Math.min(page.pageSize, page.matchingCount - page.page * page.pageSize) &&
    page.entries.every((entry) => Number.isFinite(Date.parse(entry.endedAt)))
  );
}
