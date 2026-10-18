import { afterEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { fluteFingerings } from './flute-data';
import { FluteFingering } from './flute-fingering';
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

describe('Flute fingering guide', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('selects real fingerings from the guide and resets the variant when the note changes', async () => {
    await TestBed.configureTestingModule({ imports: [FluteFingering] }).compileComponents();
    const fixture = TestBed.createComponent(FluteFingering);
    fixture.componentRef.setInput('referenceGuide', true);
    fixture.componentRef.setInput('pitch', 'Bb4');
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelectorAll('.diagram [role="button"]')).toHaveLength(12);
    expect(root.querySelector<HTMLDetailsElement>('.anatomy')?.open).toBe(true);
    const select = root.querySelector<HTMLSelectElement>('select');
    if (!select) throw new Error('B flat must offer an alternative');
    select.value = 'thumb-bb';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(fixture.componentInstance.pressed('Bb')).toBe(true);
    root.querySelector<HTMLButtonElement>('[aria-label="Consultar digitação de Dó4"]')?.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.position()?.midi).toBe(60);
    expect(fixture.componentInstance.variant()).toBe('standard');
    expect(fixture.componentInstance.pressed('C')).toBe(true);
    fixture.componentRef.setInput('pitch', 'G6');
    await fixture.whenStable();
    expect(root.querySelector('.unavailable')?.textContent).toContain(
      'Não há digitação verificada',
    );
  });
});
