export interface AudioInputDevice {
  id: string;
  name: string;
  sampleRatesHz: number[];
}

export type TunerState =
  'IDLE' | 'STARTING' | 'SILENCE' | 'LOW_CONFIDENCE' | 'UNSTABLE' | 'TRACKING' | 'ERROR';

export interface TunerObservation {
  state: TunerState;
  frequencyHz: number | null;
  nearestPitch: string | null;
  octave: number | null;
  centsDeviation: number | null;
  confidence: number;
  capturedAt: string | null;
  bufferLatencyMs: number;
  processingLatencyMs: number;
  analysisAgeMs: number;
  droppedFrames: number;
  inputOverruns: number;
  sampleRateHz: number;
  referenceHz: number;
  message: string;
}

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function finite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

export function isDevices(value: unknown): value is AudioInputDevice[] {
  return (
    Array.isArray(value) &&
    value.every(
      (device: unknown) =>
        object(device) &&
        typeof device['id'] === 'string' &&
        typeof device['name'] === 'string' &&
        Array.isArray(device['sampleRatesHz']) &&
        device['sampleRatesHz'].every((rate: unknown) => finite(rate) && rate > 0),
    )
  );
}

export function isObservation(value: unknown): value is TunerObservation {
  if (!object(value)) return false;
  const states: string[] = [
    'IDLE',
    'STARTING',
    'SILENCE',
    'LOW_CONFIDENCE',
    'UNSTABLE',
    'TRACKING',
    'ERROR',
  ];
  if (
    typeof value['state'] !== 'string' ||
    !states.includes(value['state']) ||
    typeof value['message'] !== 'string'
  )
    return false;
  for (const key of [
    'confidence',
    'bufferLatencyMs',
    'processingLatencyMs',
    'analysisAgeMs',
    'droppedFrames',
    'inputOverruns',
    'sampleRateHz',
    'referenceHz',
  ]) {
    if (!finite(value[key]) || value[key] < 0) return false;
  }
  if (!finite(value['confidence']) || value['confidence'] > 1) return false;
  if (
    value['capturedAt'] !== null &&
    (typeof value['capturedAt'] !== 'string' || !Number.isFinite(Date.parse(value['capturedAt'])))
  )
    return false;
  if (value['state'] === 'TRACKING')
    return (
      finite(value['frequencyHz']) &&
      value['frequencyHz'] > 0 &&
      finite(value['centsDeviation']) &&
      finite(value['octave']) &&
      typeof value['nearestPitch'] === 'string'
    );
  return (
    value['frequencyHz'] === null &&
    value['centsDeviation'] === null &&
    value['octave'] === null &&
    value['nearestPitch'] === null
  );
}
