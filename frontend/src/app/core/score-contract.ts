export interface MeasureView {
  index: number;
  number: string;
  startTick: number;
  durationTicks: number;
}

export interface PartState {
  id: string;
  name: string;
  mute: boolean;
  solo: boolean;
  instrumentId: string;
}

export interface TransportState {
  status: 'STOPPED' | 'PAUSED' | 'PLAYING';
  tick: number;
  totalTicks: number;
  measureIndex: number;
  measureNumber: string;
  bpm: number;
  startingBpm: number;
  metronome: boolean;
  countIn: boolean;
  countInBeatsLeft: number;
  activePartId: string;
  parts: PartState[];
}

export interface EventView {
  index: number;
  kind: 'NOTE' | 'REST';
  pitch: string;
  step: string;
  alter: number;
  octave: number;
  numerator: number;
  denominator: number;
}

export interface PartView {
  id: string;
  name: string;
  measures: { index: number; number: string; events: EventView[] }[];
}

export interface RangeWarning {
  partId: string;
  measureIndex: number;
  eventIndex: number;
  pitch: string;
  kind: 'OUTSIDE_RANGE' | 'ABOVE_COMFORT';
}

export interface InstrumentView {
  id: string;
  name: string;
  low: string;
  high: string;
  comfortableLow: string;
  comfortableHigh: string;
  soundingSemitones: number;
  midiProgram: number;
}

export interface KeyView {
  id: string;
  label: string;
  minor: boolean;
}

export interface ScoreView {
  title: string;
  filename: string;
  sha256: string;
  parserVersion: string;
  sourceKind: string;
  importWarnings: string[];
  measures: MeasureView[];
  parts: PartView[];
  revision: number;
  canUndo: boolean;
  canRedo: boolean;
  keyId: string | null;
  rangeWarnings: RangeWarning[];
  instruments: InstrumentView[];
  keys: KeyView[];
}

export interface PreviewView {
  token: string;
  description: string;
  changedNotes: number;
  changes: { partId: string; measureIndex: number; eventIndex: number; from: string; to: string }[];
  warningsBefore: RangeWarning[];
  warningsAfter: RangeWarning[];
}

export interface WorkspaceView {
  xml: string;
  score: ScoreView;
  transport: TransportState;
  preview: PreviewView | null;
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function integer(value: unknown): value is number {
  return Number.isSafeInteger(value);
}

function isRangeWarning(value: unknown): value is RangeWarning {
  return (
    record(value) &&
    typeof value['partId'] === 'string' &&
    integer(value['measureIndex']) &&
    integer(value['eventIndex']) &&
    typeof value['pitch'] === 'string' &&
    (value['kind'] === 'OUTSIDE_RANGE' || value['kind'] === 'ABOVE_COMFORT')
  );
}

function isEvent(value: unknown): value is EventView {
  return (
    record(value) &&
    integer(value['index']) &&
    (value['kind'] === 'NOTE' || value['kind'] === 'REST') &&
    typeof value['pitch'] === 'string' &&
    typeof value['step'] === 'string' &&
    integer(value['alter']) &&
    integer(value['octave']) &&
    integer(value['numerator']) &&
    integer(value['denominator'])
  );
}

export function isTransport(value: unknown): value is TransportState {
  if (!record(value) || !Array.isArray(value['parts'])) return false;
  return (
    (value['status'] === 'STOPPED' ||
      value['status'] === 'PAUSED' ||
      value['status'] === 'PLAYING') &&
    integer(value['tick']) &&
    integer(value['totalTicks']) &&
    integer(value['measureIndex']) &&
    typeof value['measureNumber'] === 'string' &&
    typeof value['bpm'] === 'number' &&
    Number.isFinite(value['bpm']) &&
    typeof value['startingBpm'] === 'number' &&
    Number.isFinite(value['startingBpm']) &&
    typeof value['metronome'] === 'boolean' &&
    typeof value['countIn'] === 'boolean' &&
    integer(value['countInBeatsLeft']) &&
    typeof value['activePartId'] === 'string' &&
    value['parts'].every(
      (part: unknown) =>
        record(part) &&
        typeof part['id'] === 'string' &&
        typeof part['name'] === 'string' &&
        typeof part['mute'] === 'boolean' &&
        typeof part['solo'] === 'boolean' &&
        typeof part['instrumentId'] === 'string',
    )
  );
}

function isScore(value: unknown): value is ScoreView {
  if (!record(value)) return false;
  return (
    typeof value['title'] === 'string' &&
    typeof value['filename'] === 'string' &&
    typeof value['sha256'] === 'string' &&
    typeof value['parserVersion'] === 'string' &&
    typeof value['sourceKind'] === 'string' &&
    Array.isArray(value['importWarnings']) &&
    value['importWarnings'].every((warning: unknown) => typeof warning === 'string') &&
    integer(value['revision']) &&
    typeof value['canUndo'] === 'boolean' &&
    typeof value['canRedo'] === 'boolean' &&
    (value['keyId'] === null || typeof value['keyId'] === 'string') &&
    Array.isArray(value['rangeWarnings']) &&
    value['rangeWarnings'].every(isRangeWarning) &&
    Array.isArray(value['instruments']) &&
    value['instruments'].every(
      (item: unknown) =>
        record(item) &&
        typeof item['id'] === 'string' &&
        typeof item['name'] === 'string' &&
        typeof item['low'] === 'string' &&
        typeof item['high'] === 'string' &&
        typeof item['comfortableLow'] === 'string' &&
        typeof item['comfortableHigh'] === 'string' &&
        integer(item['soundingSemitones']) &&
        integer(item['midiProgram']),
    ) &&
    Array.isArray(value['keys']) &&
    value['keys'].every(
      (item: unknown) =>
        record(item) &&
        typeof item['id'] === 'string' &&
        typeof item['label'] === 'string' &&
        typeof item['minor'] === 'boolean',
    ) &&
    Array.isArray(value['parts']) &&
    value['parts'].every(
      (part: unknown) =>
        record(part) &&
        typeof part['id'] === 'string' &&
        typeof part['name'] === 'string' &&
        Array.isArray(part['measures']) &&
        part['measures'].every(
          (measure: unknown) =>
            record(measure) &&
            integer(measure['index']) &&
            typeof measure['number'] === 'string' &&
            Array.isArray(measure['events']) &&
            measure['events'].every(isEvent),
        ),
    ) &&
    Array.isArray(value['measures']) &&
    value['measures'].every(
      (measure: unknown) =>
        record(measure) &&
        integer(measure['index']) &&
        typeof measure['number'] === 'string' &&
        integer(measure['startTick']) &&
        integer(measure['durationTicks']),
    )
  );
}

export function isWorkspace(value: unknown): value is WorkspaceView {
  if (!record(value) || !isScore(value['score']) || !isTransport(value['transport'])) return false;
  const preview = value['preview'];
  return (
    typeof value['xml'] === 'string' &&
    (preview === null ||
      (record(preview) &&
        typeof preview['token'] === 'string' &&
        typeof preview['description'] === 'string' &&
        integer(preview['changedNotes']) &&
        Array.isArray(preview['changes']) &&
        preview['changes'].every(
          (change: unknown) =>
            record(change) &&
            typeof change['partId'] === 'string' &&
            integer(change['measureIndex']) &&
            integer(change['eventIndex']) &&
            typeof change['from'] === 'string' &&
            typeof change['to'] === 'string',
        ) &&
        Array.isArray(preview['warningsBefore']) &&
        preview['warningsBefore'].every(isRangeWarning) &&
        Array.isArray(preview['warningsAfter']) &&
        preview['warningsAfter'].every(isRangeWarning)))
  );
}
