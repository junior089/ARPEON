import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AudioSettings } from '../core/audio-settings';
import { AudioPreparation } from './audio-preparation';
import { REFERENCE_AUDIO_FACTORY } from '../core/reference-tone';

afterEach(() => TestBed.resetTestingModule());
describe('audio preparation controls', () => {
  it('shows the shared selected input when a different area creates new controls', async () => {
    await TestBed.configureTestingModule({ imports: [AudioPreparation] }).compileComponents();
    const settings = TestBed.inject(AudioSettings);
    settings.devices.set([
      { id: 'default', name: 'Default', sampleRatesHz: [48000] },
      { id: 'usb', name: 'USB', sampleRatesHz: [48000] },
    ]);
    settings.selectedDevice.set('usb');
    settings.deviceName.set('USB');
    settings.referenceHz.set(442.1);
    const first = TestBed.createComponent(AudioPreparation);
    await first.whenStable();
    expect((first.nativeElement as HTMLElement).querySelector('select')?.value).toBe('usb');
    first.destroy();
    const next = TestBed.createComponent(AudioPreparation);
    await next.whenStable();
    expect((next.nativeElement as HTMLElement).querySelector('select')?.value).toBe('usb');
    expect((next.nativeElement as HTMLElement).querySelector('input')?.value).toBe('442.1');
  });
  it('preserves the missing choice visibly and locks controls during capture without creating output', async () => {
    const factory = vi.fn<() => AudioContext>();
    await TestBed.configureTestingModule({
      imports: [AudioPreparation],
      providers: [{ provide: REFERENCE_AUDIO_FACTORY, useValue: factory }],
    }).compileComponents();
    const settings = TestBed.inject(AudioSettings);
    settings.devices.set([{ id: 'other', name: 'Other', sampleRatesHz: [48000] }]);
    settings.selectedDevice.set('gone');
    settings.deviceName.set('USB desconectado');
    const fixture = TestBed.createComponent(AudioPreparation);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('select')?.value).toBe('gone');
    expect(element.textContent).toContain('Reconecte-a');
    fixture.componentRef.setInput('captureActive', true);
    await fixture.whenStable();
    expect(element.querySelector('select')?.disabled).toBe(true);
    expect(element.querySelector('input')?.disabled).toBe(true);
    expect(factory).not.toHaveBeenCalled();
  });
});
