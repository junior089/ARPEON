import { describe, expect, it } from 'vitest';
import { fluteFingerings } from './flute-data';
describe('Boehm flute touch-key data', () => {
  it('distinguishes low C, C sharp and D foot keys', () => {
    expect(fluteFingerings('C4')?.variants[0]?.pressed).toContain('C');
    expect(fluteFingerings('C#4')?.variants[0]?.pressed).toContain('Cs');
    expect(fluteFingerings('C#4')?.variants[0]?.pressed).not.toContain('C');
    expect(fluteFingerings('D4')?.variants[0]?.pressed).not.toContain('Eb');
    expect(fluteFingerings('D#4')?.variants[0]?.pressed).toContain('Eb');
  });
  it('keeps enharmonic spelling while looking up sounding keys', () => {
    expect(fluteFingerings('D♭4')?.variants).toEqual(fluteFingerings('C#4')?.variants);
    expect(fluteFingerings('D♭4')?.label).toBe('Ré♭4');
    expect(fluteFingerings('Ebb4')?.variants).toEqual(fluteFingerings('D4')?.variants);
  });
  it('uses separate register data and only verified alternatives', () => {
    expect(fluteFingerings('C5')?.variants[0]?.pressed).toEqual(['L1', 'Eb']);
    expect(fluteFingerings('C6')?.variants[0]?.pressed).toEqual(['T', 'L2', 'Eb']);
    expect(fluteFingerings('F6')?.variants[0]?.pressed).toEqual(['T', 'L1', 'L3', 'R1', 'Eb']);
    expect(fluteFingerings('Bb4')?.variants.map((v) => v.id)).toEqual(['standard', 'thumb-bb']);
    expect(fluteFingerings('F#5')?.variants[1]?.pressed).toContain('R2');
    expect(fluteFingerings('B3')).toBeNull();
    expect(fluteFingerings('G6')).toBeNull();
  });
});
