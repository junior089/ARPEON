import { describe, expect, it } from 'vitest';
import type { EventView } from '../../core/score-contract';
import { durationName, durationProblem, figureDuration, identifyFigure } from './rhythm-duration';

function event(
  index: number,
  onsetNumerator: number,
  kind: 'NOTE' | 'REST',
  voiceId = '1',
): EventView {
  return {
    index,
    voiceId,
    eventIndex: index,
    onsetNumerator,
    onsetDenominator: 4,
    kind,
    pitch: kind === 'NOTE' ? 'C4' : '',
    step: 'C',
    octave: 4,
    alter: 0,
    numerator: 1,
    denominator: 4,
    tieStart: false,
    tieStop: false,
  };
}
const meter = { numerator: 1, denominator: 1 };

describe('Musical durations', () => {
  it('identifies equivalent fractions and both dot counts without rewriting tuplets', () => {
    expect(identifyFigure({ numerator: 2, denominator: 8 })).toEqual({ denominator: 4, dots: 0 });
    expect(figureDuration(4, 1)).toEqual({ numerator: 3, denominator: 8 });
    expect(durationName(figureDuration(8, 2))).toBe('Colcheia com dois pontos');
    expect(durationName({ numerator: 1, denominator: 12 })).toBe('1/12 de semibreve');
    expect(identifyFigure({ numerator: 1, denominator: 12 })).toBeNull();
  });
  it('allows shortening, then growing into adjacent rests, exactly up to the next note', () => {
    const events = [event(0, 0, 'NOTE'), event(1, 1, 'REST'), event(2, 2, 'NOTE')];
    expect(durationProblem({ numerator: 1, denominator: 8 }, events[0], events, meter)).toBe('');
    expect(durationProblem({ numerator: 1, denominator: 2 }, events[0], events, meter)).toBe('');
    expect(durationProblem({ numerator: 3, denominator: 4 }, events[0], events, meter)).toContain(
      'outra nota',
    );
  });
  it('respects gaps and ignores the simultaneous notes of other voices', () => {
    const events = [event(0, 0, 'NOTE'), event(1, 0, 'NOTE', '2'), event(2, 3, 'NOTE')];
    expect(durationProblem({ numerator: 3, denominator: 4 }, events[0], events, meter)).toBe('');
  });
  it('limits a final event to the meter even with no following event', () => {
    const events = [event(0, 3, 'NOTE')];
    expect(durationProblem({ numerator: 1, denominator: 4 }, events[0], events, meter)).toBe('');
    expect(durationProblem({ numerator: 3, denominator: 8 }, events[0], events, meter)).toContain(
      'fim do compasso',
    );
  });
  it('limits inserting notes or splitting rests to the selected rest', () => {
    const events = [event(0, 0, 'REST'), event(1, 1, 'REST')];
    expect(durationProblem({ numerator: 1, denominator: 2 }, events[0], events, meter)).toBe('');
    expect(
      durationProblem({ numerator: 1, denominator: 2 }, events[0], events, meter, true),
    ).toContain('pausa selecionada');
  });
  it('rejects blank, negative, fractional and unsafe fractions before the API', () => {
    for (const numerator of [0, -1, 1.5, NaN, Infinity, 1921]) {
      expect(durationProblem({ numerator, denominator: 4 }, undefined, [], null)).toContain(
        'inteiros',
      );
    }
    expect(durationProblem({ numerator: 1, denominator: 0 }, undefined, [], null)).toContain(
      'inteiros',
    );
  });
  it('uses exact arithmetic for triplets at a following note boundary', () => {
    const first = { ...event(0, 0, 'NOTE'), numerator: 1, denominator: 12 };
    const next = { ...event(1, 1, 'NOTE'), onsetNumerator: 1, onsetDenominator: 3 };
    expect(durationProblem({ numerator: 1, denominator: 3 }, first, [first, next], meter)).toBe('');
    expect(
      durationProblem({ numerator: 641, denominator: 1920 }, first, [first, next], meter),
    ).toContain('outra nota');
  });
  it('explains unsupported subdivisions before playback while accepting triplets', () => {
    expect(durationProblem(figureDuration(128, 1), undefined, [], null)).toContain(
      'resolução rítmica',
    );
    expect(durationProblem({ numerator: 1, denominator: 12 }, undefined, [], null)).toBe('');
  });
});
