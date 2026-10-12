import { parsePitch, pitchLabel } from '../score/music-reading';
export type FluteKey =
  'T' | 'Bb' | 'L1' | 'L2' | 'L3' | 'Gs' | 'R1' | 'R2' | 'R3' | 'Eb' | 'Cs' | 'C';
export interface Fingering {
  id: string;
  name: string;
  pressed: readonly FluteKey[];
  note?: string;
}
export const FLUTE_DATA_SOURCE = 'flute-cc0-chart-v2';
// Explicit Boehm C-foot touch-key states from the bundled CC0 chart;
// thumb Bb and middle-finger F# alternatives checked against Yamaha's guide.
const STANDARD: readonly string[] = [
  'T L1 L2 L3 R1 R2 R3 Cs C',
  'T L1 L2 L3 R1 R2 R3 Cs',
  'T L1 L2 L3 R1 R2 R3',
  'T L1 L2 L3 R1 R2 R3 Eb',
  'T L1 L2 L3 R1 R2 Eb',
  'T L1 L2 L3 R1 Eb',
  'T L1 L2 L3 R3 Eb',
  'T L1 L2 L3 Eb',
  'T L1 L2 L3 Gs Eb',
  'T L1 L2 Eb',
  'T L1 R1 Eb',
  'T L1 Eb',
  'L1 Eb',
  'Eb',
  'T L2 L3 R1 R2 R3',
  'T L2 L3 R1 R2 R3 Eb',
  'T L1 L2 L3 R1 R2 Eb',
  'T L1 L2 L3 R1 Eb',
  'T L1 L2 L3 R3 Eb',
  'T L1 L2 L3 Eb',
  'T L1 L2 L3 Gs Eb',
  'T L1 L2 Eb',
  'T L1 R1 Eb',
  'T L1 Eb',
  'T L2 Eb',
  'L1 L2 Eb',
  'T L2 L3 Eb',
  'T L1 L2 L3 Gs R1 R2 R3 Eb',
  'T L1 L2 R1 R2 Eb',
  'T L1 L3 R1 Eb',
];
export function fluteFingerings(
  text: string | null,
): { label: string; midi: number; variants: Fingering[] } | null {
  const pitch = parsePitch(text);
  if (!pitch || pitch.midi < 60 || pitch.midi > 89) return null;
  const keys = STANDARD[pitch.midi - 60];
  if (!keys) return null;
  const pressed = keys.split(' ') as FluteKey[];
  const variants: Fingering[] = [{ id: 'standard', name: 'Padrão', pressed }];
  if (pitch.midi === 70 || pitch.midi === 82)
    variants.push({
      id: 'thumb-bb',
      name: 'Polegar Si♭',
      pressed: ['Bb', 'L1', 'Eb'],
      note: 'Útil em passagens sem Si natural. Não mantenha Si♭ para Fá♯6.',
    });
  if (pitch.midi === 66 || pitch.midi === 78)
    variants.push({
      id: 'middle-fs',
      name: 'Fá♯ com dedo médio',
      pressed: ['T', 'L1', 'L2', 'L3', 'R2', 'Eb'],
      note: 'Alternativa de passagem; confira a afinação no instrumento.',
    });
  return { label: pitchLabel(pitch, 'portuguese'), midi: pitch.midi, variants };
}
export const FLUTE_KEYS: readonly { id: FluteKey; label: string; x: number; y: number }[] = [
  { id: 'T', label: 'Polegar · Si', x: 64, y: 64 },
  { id: 'Bb', label: 'Polegar · Si bemol', x: 96, y: 64 },
  { id: 'L1', label: 'Indicador esquerdo', x: 76, y: 30 },
  { id: 'L2', label: 'Médio esquerdo', x: 114, y: 30 },
  { id: 'L3', label: 'Anelar esquerdo', x: 152, y: 30 },
  { id: 'Gs', label: 'Mínimo esquerdo · Sol sustenido', x: 178, y: 64 },
  { id: 'R1', label: 'Indicador direito', x: 216, y: 30 },
  { id: 'R2', label: 'Médio direito', x: 254, y: 30 },
  { id: 'R3', label: 'Anelar direito', x: 292, y: 30 },
  { id: 'Eb', label: 'Mínimo direito · Mi bemol', x: 316, y: 64 },
  { id: 'Cs', label: 'Rodízio · Dó sustenido', x: 354, y: 30 },
  { id: 'C', label: 'Rodízio · Dó', x: 390, y: 30 },
];
