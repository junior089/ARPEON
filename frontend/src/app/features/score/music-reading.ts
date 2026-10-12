import { identifyFigure } from './rhythm-duration';

export interface WrittenPitch {
  step: string;
  alter: number;
  octave: number;
  midi: number;
}
const SEMITONES: Record<string, number> = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };
const PORTUGUESE: Record<string, string> = {
  C: 'Dó',
  D: 'Ré',
  E: 'Mi',
  F: 'Fá',
  G: 'Sol',
  A: 'Lá',
  B: 'Si',
};
export function parsePitch(text: string | null): WrittenPitch | null {
  const match = /^([A-G])(##|bb|♯♯|♭♭|#|b|♯|♭)?(-?\d+)$/.exec(text ?? '');
  if (!match) return null;
  const step = match[1];
  if (!step || SEMITONES[step] === undefined) return null;
  const mark = match[2] ?? '';
  const alter = mark ? (/^[#♯]/.test(mark) ? mark.length : -mark.length) : 0;
  const octave = Number(match[3]);
  if (!Number.isSafeInteger(octave) || octave < -1 || octave > 9) return null;
  return { step, alter, octave, midi: (octave + 1) * 12 + SEMITONES[step] + alter };
}
export function pitchLabel(
  pitch: WrittenPitch,
  language: 'letters' | 'portuguese' = 'letters',
  octave = true,
): string {
  const mark = pitch.alter > 0 ? '♯'.repeat(pitch.alter) : '♭'.repeat(-pitch.alter);
  return (
    (language === 'portuguese' ? PORTUGUESE[pitch.step] : pitch.step) +
    mark +
    (octave ? pitch.octave : '')
  );
}
export function frequency(midi: number, a4 = 440): number {
  return a4 * 2 ** ((midi - 69) / 12);
}
export function concertPitch(pitch: WrittenPitch, semitones: number): WrittenPitch {
  if (semitones === 0) return pitch;
  const midi = pitch.midi + semitones;
  const names = ['C', 'C#', 'D', 'Eb', 'E', 'F', 'F#', 'G', 'Ab', 'A', 'Bb', 'B'];
  const name = names[((midi % 12) + 12) % 12];
  const step = name?.[0];
  if (!name || !step) throw new Error('Altura de concerto inválida.');
  return {
    step,
    alter: name.includes('#') ? 1 : name.includes('b') ? -1 : 0,
    octave: Math.floor(midi / 12) - 1,
    midi,
  };
}
export interface SymbolExplanation {
  symbol: string;
  name: string;
  meaning: string;
}
export function eventSymbols(
  event: {
    numerator: number;
    denominator: number;
    kind: string;
    tieStart?: boolean;
    tieStop?: boolean;
  },
  meter?: { numerator: number; denominator: number } | null,
): SymbolExplanation[] {
  const result: SymbolExplanation[] = [
    {
      symbol: event.kind === 'REST' ? '𝄽' : '♪',
      name: event.kind === 'REST' ? 'Pausa' : 'Duração',
      meaning: `${event.numerator}/${event.denominator} de semibreve${event.kind === 'REST' ? ' em silêncio' : ''}.`,
    },
  ];
  const dots = identifyFigure(event)?.dots ?? 0;
  if (dots)
    result.push({
      symbol: '·'.repeat(dots),
      name: 'Ponto de aumento',
      meaning:
        dots === 1
          ? 'Acrescenta metade do valor da figura.'
          : 'Acrescenta metade e um quarto do valor da figura.',
    });
  if (event.tieStart || event.tieStop)
    result.push({
      symbol: '⌒',
      name: 'Ligadura de prolongamento',
      meaning: 'Une notas da mesma altura. Some suas durações e sustente o som sem novo ataque.',
    });
  if (meter)
    result.push({
      symbol: `${meter.numerator}/${meter.denominator}`,
      name: 'Fórmula de compasso',
      meaning: `${meter.numerator} unidades de 1/${meter.denominator} de semibreve por compasso. Em compassos compostos, várias unidades podem formar um pulso.`,
    });
  return result;
}

export function measureSymbols(
  xml: string | Document,
  partId: string,
  index: number,
): SymbolExplanation[] {
  const document =
    typeof xml === 'string' ? new DOMParser().parseFromString(xml, 'application/xml') : xml;
  if (document.querySelector('parsererror')) return [];
  const part = Array.from(document.querySelectorAll('score-partwise > part')).find(
    (part) => part.getAttribute('id') === partId,
  );
  const measures = Array.from(part?.querySelectorAll(':scope > measure') ?? []);
  const measure = measures[index];
  if (!measure) return [];
  let clef: Element | null = null;
  let key: Element | null = null;
  let time: Element | null = null;
  for (const previous of measures.slice(0, index + 1)) {
    clef = previous.querySelector('attributes > clef') ?? clef;
    key = previous.querySelector('attributes > key') ?? key;
    time = previous.querySelector('attributes > time') ?? time;
  }
  const result: SymbolExplanation[] = [];
  const sign = clef?.querySelector('sign')?.textContent ?? 'G';
  const line = clef?.querySelector('line')?.textContent ?? '2';
  if (sign)
    result.push({
      symbol: sign === 'G' ? '𝄞' : sign === 'F' ? '𝄢' : '𝄡',
      name: `Clave de ${sign === 'G' ? 'Sol' : sign === 'F' ? 'Fá' : 'Dó'}`,
      meaning: `A linha ${line} recebe a nota de referência da clave. A altura das notas já considera a clave e a oitava indicada no arquivo.`,
    });
  const fifths = Number(key?.querySelector('fifths')?.textContent ?? 0);
  if (key)
    result.push({
      symbol: fifths > 0 ? '♯' : fifths < 0 ? '♭' : '♮',
      name: 'Armadura de clave',
      meaning:
        fifths === 0
          ? 'Sem sustenidos ou bemóis na armadura. Acidentes locais continuam valendo conforme a notação.'
          : `${Math.abs(fifths)} ${fifths > 0 ? 'sustenido(s)' : 'bemol(is)'} na armadura. Os nomes das notas usam as alturas escritas no MusicXML, incluindo acidentes locais.`,
    });
  const beats = time?.querySelector('beats')?.textContent;
  const beatType = time?.querySelector('beat-type')?.textContent;
  if (beats && beatType)
    result.push({
      symbol: `${beats}/${beatType}`,
      name: 'Fórmula de compasso',
      meaning: `${beats} unidades de 1/${beatType} de semibreve por compasso. A unidade escrita não corresponde necessariamente ao pulso em compassos compostos.`,
    });
  for (const repeat of measure.querySelectorAll('barline > repeat'))
    result.push({
      symbol: '𝄆 𝄇',
      name:
        repeat.getAttribute('direction') === 'forward' ? 'Início da repetição' : 'Fim da repetição',
      meaning:
        repeat.getAttribute('direction') === 'forward'
          ? 'Marca o início do trecho repetido.'
          : 'Retoma o trecho indicado. A linha do transporte inclui cada ocorrência executada, sem duplicar os compassos escritos.',
    });
  for (const ending of measure.querySelectorAll('barline > ending'))
    result.push({
      symbol: `${ending.getAttribute('number')}.`,
      name: 'Casa de repetição',
      meaning: `Trecho para a passagem ${ending.getAttribute('number')}. ${ending.getAttribute('type') === 'start' ? 'Início' : 'Fim'} da casa.`,
    });
  return result;
}
