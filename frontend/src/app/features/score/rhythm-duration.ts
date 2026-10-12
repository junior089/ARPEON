import type { EventView } from '../../core/score-contract';

export const RHYTHMIC_FIGURES = [
  { denominator: 1, name: 'Semibreve' },
  { denominator: 2, name: 'Mínima' },
  { denominator: 4, name: 'Semínima' },
  { denominator: 8, name: 'Colcheia' },
  { denominator: 16, name: 'Semicolcheia' },
  { denominator: 32, name: 'Fusa' },
  { denominator: 64, name: 'Semifusa' },
  { denominator: 128, name: 'Quartifusa' },
] as const;

export interface RhythmDuration {
  numerator: number;
  denominator: number;
}

export function figureDuration(denominator: number, dots: number): RhythmDuration {
  return { numerator: 2 ** (dots + 1) - 1, denominator: denominator * 2 ** dots };
}

export function identifyFigure(
  duration: RhythmDuration,
): { denominator: number; dots: number } | null {
  if (
    !Number.isSafeInteger(duration.numerator) ||
    !Number.isSafeInteger(duration.denominator) ||
    duration.numerator <= 0 ||
    duration.denominator <= 0
  )
    return null;
  for (const figure of RHYTHMIC_FIGURES) {
    for (let dots = 0; dots <= 2; dots++) {
      const candidate = figureDuration(figure.denominator, dots);
      if (duration.numerator * candidate.denominator === candidate.numerator * duration.denominator)
        return { denominator: figure.denominator, dots };
    }
  }
  return null;
}

export function durationName(duration: RhythmDuration): string {
  const figure = identifyFigure(duration);
  if (!figure) return `${duration.numerator}/${duration.denominator} de semibreve`;
  const name =
    RHYTHMIC_FIGURES.find((candidate) => candidate.denominator === figure.denominator)?.name ??
    'Figura';
  return name + (figure.dots === 1 ? ' pontuada' : figure.dots === 2 ? ' com dois pontos' : '');
}

// Exact fractions keep tuplets and long measures from rounding across a boundary.
function fraction(duration: RhythmDuration): [bigint, bigint] {
  return [BigInt(duration.numerator), BigInt(duration.denominator)];
}
function add(a: [bigint, bigint], b: [bigint, bigint]): [bigint, bigint] {
  const numerator = a[0] * b[1] + b[0] * a[1];
  const denominator = a[1] * b[1];
  let gcd = numerator;
  let remainder = denominator;
  while (remainder !== 0n) [gcd, remainder] = [remainder, gcd % remainder];
  return [numerator / gcd, denominator / gcd];
}
function greater(a: [bigint, bigint], b: [bigint, bigint]): boolean {
  return a[0] * b[1] > b[0] * a[1];
}

export function durationProblem(
  duration: RhythmDuration,
  selected: EventView | undefined,
  events: EventView[],
  meter: RhythmDuration | null,
  inserting = false,
): string {
  if (
    !Number.isSafeInteger(duration.numerator) ||
    !Number.isSafeInteger(duration.denominator) ||
    duration.numerator < 1 ||
    duration.denominator < 1 ||
    duration.numerator > 1920 ||
    duration.denominator > 1920
  )
    return 'Use numerador e denominador inteiros entre 1 e 1920.';
  if ((BigInt(duration.numerator) * 1920n) % BigInt(duration.denominator) !== 0n)
    return 'Esta duração excede a resolução rítmica da reprodução. Escolha outra figura ou fração.';
  if (!selected) return '';
  if (inserting)
    return greater(fraction(duration), fraction(selected))
      ? 'A duração excede a pausa selecionada. Escolha uma figura menor.'
      : '';
  const voice = events.filter((event) => (event.voiceId ?? '1') === (selected.voiceId ?? '1'));
  let elapsed: [bigint, bigint] = [0n, 1n];
  let selectedEnd: [bigint, bigint] | null = null;
  for (const event of voice) {
    const onset =
      event.onsetNumerator !== undefined && event.onsetDenominator
        ? fraction({ numerator: event.onsetNumerator, denominator: event.onsetDenominator })
        : elapsed;
    if (event.index === selected.index) selectedEnd = add(onset, fraction(duration));
    else if (selectedEnd && event.kind !== 'REST' && greater(selectedEnd, onset))
      return 'A duração alcança outra nota desta voz. Escolha uma figura menor ou edite as pausas.';
    elapsed = add(onset, fraction(event));
  }
  if (meter && selectedEnd && greater(selectedEnd, fraction(meter)))
    return 'A duração ultrapassa o fim do compasso. Escolha uma figura menor.';
  return '';
}
