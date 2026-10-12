import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AudioSettings } from './audio-settings';
import { REFERENCE_AUDIO_FACTORY, ReferenceTone } from './reference-tone';

function setup(resume = Promise.resolve()) {
  const oscillator = {
    type: '',
    frequency: { setValueAtTime: vi.fn() },
    connect: vi.fn(),
    disconnect: vi.fn(),
    start: vi.fn(),
    stop: vi.fn(),
    onended: null as (() => void) | null,
  };
  const gain = {
    gain: { setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn() },
    connect: vi.fn(),
  };
  const context = {
    resume: vi.fn(() => resume),
    close: vi.fn(async () => undefined),
    currentTime: 10,
    destination: {},
    createOscillator: vi.fn(() => oscillator),
    createGain: vi.fn(() => gain),
  };
  const factory = vi.fn(() => context as unknown as AudioContext);
  TestBed.configureTestingModule({
    providers: [{ provide: REFERENCE_AUDIO_FACTORY, useValue: factory }],
  });
  return {
    tone: TestBed.inject(ReferenceTone),
    settings: TestBed.inject(AudioSettings),
    oscillator,
    context,
    factory,
  };
}
afterEach(() => TestBed.resetTestingModule());
describe('local reference tone', () => {
  it('auditions a score pitch outside the A4 calibration range without changing calibration', async () => {
    const { tone, settings, oscillator } = setup();
    await tone.playPitch(261.625565);
    expect(oscillator.frequency.setValueAtTime).toHaveBeenCalledWith(261.625565, 10.03);
    expect(settings.referenceHz()).toBe(440);
    tone.stop();
  });
  it('creates audio only on demand and plays exactly the selected A4 for two seconds', async () => {
    const { tone, settings, oscillator, context, factory } = setup();
    expect(factory).not.toHaveBeenCalled();
    await tone.play(442.1);
    expect(oscillator.frequency.setValueAtTime).toHaveBeenCalledWith(442.1, 10.03);
    expect(oscillator.stop).toHaveBeenCalledWith(12.03);
    expect(settings.referencePlaying()).toBe(true);
    oscillator.onended?.();
    expect(tone.playing()).toBe(false);
    expect(context.close).toHaveBeenCalledOnce();
  });
  it('cancels a suspended context without playing late after navigation', async () => {
    let resume!: () => void;
    const { tone, context, oscillator } = setup(
      new Promise<void>((r) => {
        resume = r;
      }),
    );
    const playing = tone.play(440);
    tone.ngOnDestroy();
    resume();
    await playing;
    expect(oscillator.start).not.toHaveBeenCalled();
    expect(context.close).toHaveBeenCalledOnce();
    expect(tone.playing()).toBe(false);
  });
  it('rejects invalid references and reports a failed output without leaving playback active', async () => {
    const { tone, factory } = setup();
    await tone.play(499);
    expect(factory).not.toHaveBeenCalled();
    factory.mockImplementation(() => {
      throw new Error('Unavailable');
    });
    await tone.play(440);
    expect(tone.error()).toContain('saída');
    expect(tone.playing()).toBe(false);
  });
});
