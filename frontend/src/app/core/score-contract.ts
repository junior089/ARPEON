export interface MeasureView {
  writtenIndex?: number;
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
  volume?: number;
  pan?: number;
  effectiveGain?: number;
  soundStatus?: 'NOT_LOADED' | 'READY' | 'ERROR';
  soundError?: string;
  soundName?: string;
}

export interface SoundBankInfo {
  id: string;
  name: string;
  filename: string;
  bytes: number;
  programs: number;
}
export function isSoundBankList(value: unknown): value is SoundBankInfo[] {
  return (
    Array.isArray(value) &&
    value.length <= 8 &&
    value.every(
      (bank: unknown) =>
        record(bank) &&
        typeof bank['id'] === 'string' &&
        /^[0-9a-f]{64}$/.test(bank['id']) &&
        typeof bank['name'] === 'string' &&
        typeof bank['filename'] === 'string' &&
        level(bank['bytes'], 1, 128 * 1024 * 1024) &&
        level(bank['programs'], 1, 128),
    )
  );
}

export interface TransportState {
  mixPresets?: { id: string; name: string }[];
  markers?: { id: string; name: string; startMeasureIndex: number; endMeasureIndex: number }[];
  tempoPractice?: { enabled: boolean; stepBpm: number; targetBpm: number; loopsPerStep: number };
  completedLoops?: number;
  soundBankId?: string;
  soundBankName?: string;
  soundBankError?: string;
  master?: number;
  loop?: {
    startTick: number;
    endTick: number;
    startMeasureIndex: number;
    endMeasureIndex: number;
  } | null;
  status: 'STOPPED' | 'PAUSED' | 'PLAYING';
  tick: number;
  audibleTick?: number;
  audibleBpm?: number;
  audioLatencyMs?: number;
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

export interface EventHeadView {
  index: number;
  pitch: string;
  tieStart: boolean;
  tieStop: boolean;
}

export interface EventView {
  index: number;
  voiceId?: string;
  eventIndex?: number;
  onsetNumerator?: number;
  onsetDenominator?: number;
  kind: 'NOTE' | 'REST' | 'CHORD';
  pitch: string;
  step: string;
  alter: number;
  octave: number;
  numerator: number;
  denominator: number;
  tieStart: boolean;
  tieStop: boolean;
  heads?: EventHeadView[];
}

export interface MeasureEventsPage {
  durationNumerator?: number;
  durationDenominator?: number;
  revision: number;
  partId: string;
  index: number;
  number: string;
  events: EventView[];
}

export interface PartView {
  id: string;
  name: string;
  measures: {
    index: number;
    number: string;
    events: EventView[];
    durationNumerator?: number;
    durationDenominator?: number;
  }[];
}

export interface RangeWarning {
  partId: string;
  measureIndex: number;
  voiceId?: string;
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

export interface SourceView {
  metadata?: { title: string; composer: string; instrumentId: string } | null;
  providerId: string;
  pageUrl: string;
  fetchedAt: string;
  licenseStatus: string;
  licenseShortName: string;
  licenseUrl: string;
  notes: string[];
}

export interface ScoreView {
  title: string;
  composer?: string;
  filename: string;
  sha256: string;
  parserVersion: string;
  sourceKind: string;
  importWarnings: string[];
  source: SourceView | null;
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
  xml?: string;
  token: string;
  description: string;
  changedNotes: number;
  changes: {
    partId: string;
    measureIndex: number;
    voiceId?: string;
    eventIndex: number;
    from: string;
    to: string;
  }[];
  warningsBefore: RangeWarning[];
  warningsAfter: RangeWarning[];
}

export interface RehearsalView {
  viewMode: 'full' | 'individual' | 'focus';
  hiddenParts: string[];
}

export interface PartImportReview {
  token: string;
  filename: string;
  parts: {
    id: string;
    name: string;
    instrumentId: string;
    measures: number;
    compatible: boolean;
    problem: string;
  }[];
}

export interface AlignmentPreview {
  valid: boolean;
  parts: {
    id: string;
    name: string;
    problem: string;
    totalMeasures: number;
    rows: {
      sourcePosition: number;
      targetPosition: number;
      sourceNumber: string;
      targetNumber: string;
      sourceMeter: string;
      targetMeter: string;
      sourceDuration: string;
      targetDuration: string;
      sourceBpm: number;
      targetBpm: number;
      pickup: boolean;
    }[];
  }[];
}

export function isAlignmentPreview(value: unknown): value is AlignmentPreview {
  return (
    record(value) &&
    typeof value['valid'] === 'boolean' &&
    Array.isArray(value['parts']) &&
    value['parts'].every(
      (part: unknown) =>
        record(part) &&
        typeof part['id'] === 'string' &&
        typeof part['name'] === 'string' &&
        typeof part['problem'] === 'string' &&
        integer(part['totalMeasures']) &&
        Array.isArray(part['rows']) &&
        part['rows'].every(
          (row: unknown) =>
            record(row) &&
            integer(row['sourcePosition']) &&
            integer(row['targetPosition']) &&
            typeof row['pickup'] === 'boolean' &&
            [
              'sourceNumber',
              'targetNumber',
              'sourceMeter',
              'targetMeter',
              'sourceDuration',
              'targetDuration',
            ].every((key) => typeof row[key] === 'string') &&
            level(row['sourceBpm'], 0, 1000) &&
            level(row['targetBpm'], 0, 1000),
        ),
    )
  );
}

export function isPartImportReview(value: unknown): value is PartImportReview {
  return (
    record(value) &&
    typeof value['token'] === 'string' &&
    typeof value['filename'] === 'string' &&
    Array.isArray(value['parts']) &&
    value['parts'].length > 0 &&
    value['parts'].every(
      (part: unknown) =>
        record(part) &&
        typeof part['id'] === 'string' &&
        typeof part['name'] === 'string' &&
        typeof part['instrumentId'] === 'string' &&
        integer(part['measures']) &&
        part['measures'] > 0 &&
        typeof part['compatible'] === 'boolean' &&
        typeof part['problem'] === 'string',
    )
  );
}

export interface WorkspaceView {
  rehearsal?: RehearsalView;
  xml: string;
  score: ScoreView;
  transport: TransportState;
  preview: PreviewView | null;
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function level(value: unknown, min: number, max: number): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max;
}

function integer(value: unknown): value is number {
  return Number.isSafeInteger(value);
}

function isRangeWarning(value: unknown): value is RangeWarning {
  return (
    record(value) &&
    typeof value['partId'] === 'string' &&
    integer(value['measureIndex']) &&
    (value['voiceId'] === undefined || typeof value['voiceId'] === 'string') &&
    integer(value['eventIndex']) &&
    typeof value['pitch'] === 'string' &&
    (value['kind'] === 'OUTSIDE_RANGE' || value['kind'] === 'ABOVE_COMFORT')
  );
}

function isEvent(value: unknown): value is EventView {
  return (
    record(value) &&
    integer(value['index']) &&
    (value['voiceId'] === undefined || typeof value['voiceId'] === 'string') &&
    (value['eventIndex'] === undefined || integer(value['eventIndex'])) &&
    (value['onsetNumerator'] === undefined || integer(value['onsetNumerator'])) &&
    (value['onsetDenominator'] === undefined || integer(value['onsetDenominator'])) &&
    (value['kind'] === 'NOTE' || value['kind'] === 'REST' || value['kind'] === 'CHORD') &&
    typeof value['pitch'] === 'string' &&
    typeof value['step'] === 'string' &&
    integer(value['alter']) &&
    integer(value['octave']) &&
    integer(value['numerator']) &&
    integer(value['denominator']) &&
    typeof value['tieStart'] === 'boolean' &&
    typeof value['tieStop'] === 'boolean' &&
    (value['heads'] === undefined ||
      (Array.isArray(value['heads']) &&
        value['heads'].every(
          (head: unknown, index: number) =>
            record(head) &&
            integer(head['index']) &&
            head['index'] === index &&
            typeof head['pitch'] === 'string' &&
            typeof head['tieStart'] === 'boolean' &&
            typeof head['tieStop'] === 'boolean',
        )))
  );
}

export function isMeasureEventsPage(value: unknown): value is MeasureEventsPage {
  return (
    record(value) &&
    ((value['durationNumerator'] === undefined && value['durationDenominator'] === undefined) ||
      (integer(value['durationNumerator']) &&
        value['durationNumerator'] > 0 &&
        integer(value['durationDenominator']) &&
        value['durationDenominator'] > 0)) &&
    integer(value['revision']) &&
    typeof value['partId'] === 'string' &&
    integer(value['index']) &&
    typeof value['number'] === 'string' &&
    Array.isArray(value['events']) &&
    value['events'].every(isEvent) &&
    value['events'].every((event: EventView, index: number) => event.index === index)
  );
}

export function isTransport(value: unknown): value is TransportState {
  if (!record(value) || !Array.isArray(value['parts'])) return false;
  return (
    (value['status'] === 'STOPPED' ||
      value['status'] === 'PAUSED' ||
      value['status'] === 'PLAYING') &&
    (value['master'] === undefined || level(value['master'], 0, 1)) &&
    ['soundBankId', 'soundBankName', 'soundBankError'].every(
      (key) => value[key] === undefined || typeof value[key] === 'string',
    ) &&
    (value['mixPresets'] === undefined ||
      (Array.isArray(value['mixPresets']) &&
        value['mixPresets'].length <= 12 &&
        value['mixPresets'].every(
          (preset: unknown) =>
            record(preset) &&
            typeof preset['id'] === 'string' &&
            typeof preset['name'] === 'string',
        ))) &&
    (value['markers'] === undefined ||
      (Array.isArray(value['markers']) &&
        value['markers'].length <= 24 &&
        value['markers'].every(
          (marker: unknown) =>
            record(marker) &&
            typeof marker['id'] === 'string' &&
            typeof marker['name'] === 'string' &&
            integer(marker['startMeasureIndex']) &&
            integer(marker['endMeasureIndex']) &&
            marker['startMeasureIndex'] >= 0 &&
            marker['endMeasureIndex'] >= marker['startMeasureIndex'],
        ))) &&
    (value['completedLoops'] === undefined ||
      (integer(value['completedLoops']) && value['completedLoops'] >= 0)) &&
    (value['tempoPractice'] === undefined ||
      (record(value['tempoPractice']) &&
        typeof value['tempoPractice']['enabled'] === 'boolean' &&
        level(value['tempoPractice']['stepBpm'], 1, 20) &&
        level(value['tempoPractice']['targetBpm'], 30, 300) &&
        level(value['tempoPractice']['loopsPerStep'], 1, 20))) &&
    integer(value['tick']) &&
    (value['audibleTick'] === undefined || integer(value['audibleTick'])) &&
    (value['audibleBpm'] === undefined ||
      (typeof value['audibleBpm'] === 'number' &&
        Number.isFinite(value['audibleBpm']) &&
        value['audibleBpm'] > 0)) &&
    (value['audioLatencyMs'] === undefined ||
      (typeof value['audioLatencyMs'] === 'number' &&
        Number.isFinite(value['audioLatencyMs']) &&
        value['audioLatencyMs'] >= 0)) &&
    integer(value['totalTicks']) &&
    (value['loop'] === undefined ||
      value['loop'] === null ||
      (record(value['loop']) &&
        integer(value['loop']['startTick']) &&
        integer(value['loop']['endTick']) &&
        value['loop']['startTick'] >= 0 &&
        value['loop']['endTick'] > value['loop']['startTick'] &&
        value['loop']['endTick'] <= value['totalTicks'] &&
        integer(value['loop']['startMeasureIndex']) &&
        integer(value['loop']['endMeasureIndex']) &&
        value['loop']['startMeasureIndex'] >= 0 &&
        value['loop']['endMeasureIndex'] >= value['loop']['startMeasureIndex'])) &&
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
        typeof part['instrumentId'] === 'string' &&
        (part['volume'] === undefined || level(part['volume'], 0, 1)) &&
        (part['pan'] === undefined || level(part['pan'], -1, 1)) &&
        (part['effectiveGain'] === undefined || level(part['effectiveGain'], 0, 1)) &&
        (part['soundStatus'] === undefined ||
          ['NOT_LOADED', 'READY', 'ERROR'].includes(String(part['soundStatus']))) &&
        (part['soundError'] === undefined || typeof part['soundError'] === 'string'),
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
    (value['source'] === null ||
      (record(value['source']) &&
        typeof value['source']['providerId'] === 'string' &&
        typeof value['source']['pageUrl'] === 'string' &&
        typeof value['source']['fetchedAt'] === 'string' &&
        typeof value['source']['licenseStatus'] === 'string' &&
        typeof value['source']['licenseShortName'] === 'string' &&
        typeof value['source']['licenseUrl'] === 'string' &&
        Array.isArray(value['source']['notes']) &&
        value['source']['notes'].every((note: unknown) => typeof note === 'string'))) &&
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
        (measure['writtenIndex'] === undefined ||
          (integer(measure['writtenIndex']) && measure['writtenIndex'] >= 0)) &&
        integer(measure['startTick']) &&
        integer(measure['durationTicks']),
    )
  );
}

export function isWorkspace(value: unknown): value is WorkspaceView {
  if (!record(value) || !isScore(value['score']) || !isTransport(value['transport'])) return false;
  const preview = value['preview'];
  return (
    (value['rehearsal'] === undefined ||
      (record(value['rehearsal']) &&
        ['full', 'individual', 'focus'].includes(String(value['rehearsal']['viewMode'])) &&
        Array.isArray(value['rehearsal']['hiddenParts']) &&
        value['rehearsal']['hiddenParts'].every((id: unknown) => typeof id === 'string'))) &&
    typeof value['xml'] === 'string' &&
    (preview === null ||
      (record(preview) &&
        typeof preview['token'] === 'string' &&
        typeof preview['description'] === 'string' &&
        (preview['xml'] === undefined || typeof preview['xml'] === 'string') &&
        integer(preview['changedNotes']) &&
        Array.isArray(preview['changes']) &&
        preview['changes'].every(
          (change: unknown) =>
            record(change) &&
            typeof change['partId'] === 'string' &&
            integer(change['measureIndex']) &&
            (change['voiceId'] === undefined || typeof change['voiceId'] === 'string') &&
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
