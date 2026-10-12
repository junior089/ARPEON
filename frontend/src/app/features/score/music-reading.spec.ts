import { describe, expect, it } from 'vitest';
import {
  parsePitch,
  pitchLabel,
  concertPitch,
  frequency,
  eventSymbols,
  measureSymbols,
} from './music-reading';
function pitch(text: string) {
  const result = parsePitch(text);
  if (!result) throw new Error('Invalid test pitch');
  return result;
}

describe('musical reading', () => {
  it('preserves written spelling and octave across enharmonic equivalents', () => {
    const written = pitch('Ebb4');
    expect(written.midi).toBe(62);
    expect(pitchLabel(written, 'portuguese')).toBe('Mi♭♭4');
    expect(parsePitch('D4')?.midi).toBe(written.midi);
    expect(parsePitch('B#3')?.midi).toBe(parsePitch('C4')?.midi);
    expect(pitchLabel(pitch('F##5'), 'letters', false)).toBe('F♯♯');
    expect(parsePitch('H4')).toBeNull();
    expect(parsePitch('C10')).toBeNull();
  });
  it('separates written pitch from B-flat instrument sound and calibrated audition', () => {
    const written = pitch('C5');
    const sounding = concertPitch(written, -2);
    expect(pitchLabel(sounding)).toBe('B♭4');
    expect(written.step).toBe('C');
    expect(frequency(69, 442)).toBe(442);
    expect(frequency(81, 442)).toBe(884);
  });
  it('explains ties without assuming a universal fermata duration', () => {
    const symbols = eventSymbols(
      { numerator: 3, denominator: 8, kind: 'NOTE', tieStart: true },
      { numerator: 6, denominator: 8 },
    );
    expect(symbols.map((symbol) => symbol.name)).toContain('Ligadura de prolongamento');
    expect(symbols.find((symbol) => symbol.name === 'Fórmula de compasso')?.meaning).toContain(
      'compassos compostos',
    );
    expect(symbols.some((symbol) => symbol.name === 'Fermata')).toBe(false);
  });
  it('inherits real clef and key instructions and reads repeat direction', () => {
    const xml =
      '<score-partwise><part id="P1"><measure><attributes><clef><sign>F</sign><line>4</line></clef><key><fifths>-2</fifths></key></attributes></measure><measure><barline><repeat direction="backward"/><ending number="1" type="stop"/></barline></measure></part></score-partwise>';
    const symbols = measureSymbols(xml, 'P1', 1);
    expect(symbols.map((item) => item.name)).toEqual([
      'Clave de Fá',
      'Armadura de clave',
      'Fim da repetição',
      'Casa de repetição',
    ]);
    expect(symbols[1]?.meaning).toContain('2 bemol');
    expect(measureSymbols(xml, 'P2', 0)).toEqual([]);
  });
  it('keeps the written time signature instead of reducing it to a measure duration', () => {
    const xml =
      '<score-partwise><part id="P1"><measure><attributes><time><beats>4</beats><beat-type>4</beat-type></time></attributes></measure><measure/></part></score-partwise>';
    expect(
      measureSymbols(xml, 'P1', 1).find((symbol) => symbol.name === 'Fórmula de compasso')?.symbol,
    ).toBe('4/4');
  });
});
