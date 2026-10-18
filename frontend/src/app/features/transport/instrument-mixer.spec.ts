import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ScoreSession } from '../score/score-session';
import { InstrumentMixer } from './instrument-mixer';

describe('instrument mixer', () => {
  it('operates each channel, master and presets without changing the selected notation', async () => {
    const action = vi.fn();
    const transport = signal({
      activePartId: 'flute',
      master: 0.8,
      parts: [
        {
          id: 'flute',
          name: 'Flauta',
          instrumentId: 'flute',
          volume: 0.4,
          pan: 0,
          mute: true,
          solo: false,
          soundStatus: 'READY',
        },
        {
          id: 'violin',
          name: 'Violino',
          instrumentId: 'violin',
          volume: 0.6,
          pan: 0.2,
          mute: false,
          solo: false,
          soundStatus: 'ERROR',
          soundError: 'Timbre indisponível',
        },
      ],
    });
    await TestBed.configureTestingModule({
      imports: [InstrumentMixer],
      providers: [
        {
          provide: ScoreSession,
          useValue: {
            transport,
            score: signal({ instruments: [{ id: 'flute', name: 'Flauta transversal' }] }),
            busy: signal(false),
            action,
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(InstrumentMixer);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement as HTMLElement;
    const violinVolume = root.querySelector<HTMLInputElement>('[aria-label="Volume de Violino"]');
    if (!violinVolume) throw new Error('Missing violin volume control');
    violinVolume.value = '25';
    violinVolume.dispatchEvent(new Event('change'));
    expect(action).toHaveBeenCalledWith('volume', { id: 'violin', value: 0.25 });
    root.querySelector<HTMLButtonElement>('[aria-label="Solo Violino"]')?.click();
    expect(action).toHaveBeenCalledWith('solo', { id: 'violin', enabled: true });
    expect(transport().activePartId).toBe('flute');
    expect(root.textContent).toContain('Timbre indisponível');
    const preset = root.querySelector<HTMLSelectElement>('[aria-label="Preset de ensaio"]');
    if (!preset) throw new Error('Missing rehearsal selector');
    preset.value = 'practice';
    preset.dispatchEvent(new Event('change'));
    expect(action).toHaveBeenCalledWith('preset', { preset: 'practice' });
    const master = root.querySelector<HTMLInputElement>('.master-level input');
    if (!master) throw new Error('Missing master control');
    master.value = '50';
    master.dispatchEvent(new Event('change'));
    expect(action).toHaveBeenCalledWith('master', { value: 0.5 });
  });
});
