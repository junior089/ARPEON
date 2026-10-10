import { describe, expect, it } from 'vitest';
import { fluteChartPosition } from './flute-fingering';

describe('flute fingering chart lookup', () => {
  it('selects the correct cell by pitch and octave', () => {
    expect(fluteChartPosition('C4')).toEqual({ label: 'Dó 4', position: '0px 0px' });
    expect(fluteChartPosition('G5')).toEqual({ label: 'Sol 5', position: '-287px -546px' });
    expect(fluteChartPosition('F6')).toEqual({ label: 'Fá 6', position: '-574px -390px' });
  });

  it('maps enharmonic flats and excludes notes outside the local chart', () => {
    expect(fluteChartPosition('D♭4')?.position).toBe(fluteChartPosition('C#4')?.position);
    expect(fluteChartPosition('D♭4')?.label).toBe('Ré♭ 4');
    expect(fluteChartPosition('Ebb4')?.position).toBe(fluteChartPosition('D4')?.position);
    expect(fluteChartPosition('C7')).toBeNull();
    expect(fluteChartPosition(null)).toBeNull();
  });
});
