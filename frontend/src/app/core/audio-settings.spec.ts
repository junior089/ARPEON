import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AudioSettings } from './audio-settings';
import { LOCAL_FETCH, SESSION_TOKEN } from './local-session';
import { TunerSession } from '../features/tuner/tuner-session';

describe('shared audio preparation', () => {
  const request = vi.fn<typeof fetch>();
  const devices = [{ id: 'usb', name: 'Microfone USB', sampleRatesHz: [48000] }];
  const saved = {
    schemaVersion: 1,
    deviceId: 'usb',
    deviceName: 'Microfone USB',
    referenceHz: 442.1,
  };
  let settings: AudioSettings;
  beforeEach(() => {
    request.mockReset();
    request.mockImplementation(async (path, init) =>
      Response.json(
        path === '/api/devices'
          ? devices
          : init?.method === 'PUT'
            ? JSON.parse(String(init.body))
            : saved,
      ),
    );
    TestBed.configureTestingModule({
      providers: [
        TunerSession,
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'session' },
      ],
    });
    settings = TestBed.inject(AudioSettings);
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });
  it('restores one shared configuration, deduplicates discovery, and leaves capture off', async () => {
    await Promise.all([settings.refresh(), settings.refresh()]);
    expect(settings.referenceHz()).toBe(442.1);
    expect(settings.selectedDevice()).toBe('usb');
    expect(settings.ready()).toBe(true);
    const tuner = TestBed.inject(TunerSession);
    expect(tuner.referenceHz()).toBe(442.1);
    expect(tuner.active()).toBe(false);
    expect(request.mock.calls.map(([path]) => path)).toEqual([
      '/api/audio/preferences',
      '/api/devices',
    ]);
  });
  it('keeps a missing saved input instead of silently listening through a different device', async () => {
    request.mockImplementation(async (path) =>
      Response.json(
        path === '/api/devices'
          ? devices
          : { ...saved, deviceId: 'gone', deviceName: 'USB desconectado' },
      ),
    );
    await settings.refresh();
    expect(settings.selectedDevice()).toBe('gone');
    expect(settings.missingDevice()).toBe(true);
    expect(settings.ready()).toBe(false);
    await TestBed.inject(TunerSession).start();
    expect(request.mock.calls.some(([path]) => String(path).includes('/start'))).toBe(false);
    request.mockImplementation(async (_path, init) =>
      Response.json(JSON.parse(String(init?.body))),
    );
    settings.selectDevice('usb');
    await settings.flush();
    expect(settings.ready()).toBe(true);
    expect(settings.saved()).toBe(true);
  });
  it('does not persist invalid references or activation permission', async () => {
    await settings.refresh();
    request.mockClear();
    settings.setReference(NaN);
    await settings.flush();
    expect(settings.ready()).toBe(false);
    expect(request).not.toHaveBeenCalled();
    settings.setReference(443);
    await settings.flush();
    const init = request.mock.calls[0]?.[1];
    expect(init?.method).toBe('PUT');
    expect(JSON.parse(String(init?.body))).toEqual({ ...saved, referenceHz: 443 });
    expect(String(init?.body)).not.toContain('consent');
  });
  it('serializes edits during an in-flight write and restores the latest value on retry', async () => {
    await settings.refresh();
    let resolve!: (response: Response) => void;
    request.mockImplementationOnce(
      () =>
        new Promise<Response>((r) => {
          resolve = r;
        }),
    );
    settings.setReference(443);
    const first = settings.flush();
    settings.setReference(444);
    resolve(new Response('', { status: 500 }));
    await first;
    expect(settings.preferencesError()).not.toBe('');
    expect(settings.saved()).toBe(false);
    await settings.retrySave();
    expect(JSON.parse(String(request.mock.lastCall?.[1]?.body)).referenceHz).toBe(444);
    expect(settings.saved()).toBe(true);
    expect(settings.preferencesError()).toBe('');
  });
  it('recovers discovery independently of corrupt preferences and clears stale availability', async () => {
    request.mockImplementation(async (path) =>
      path === '/api/devices'
        ? Response.json(devices)
        : Response.json({ error: 'Ajustes inválidos' }, { status: 500 }),
    );
    await settings.refresh();
    expect(settings.ready()).toBe(true);
    expect(settings.preferencesError()).toContain('inválidos');
    request.mockRejectedValue(new TypeError('network'));
    await settings.refresh();
    expect(settings.ready()).toBe(false);
    expect(settings.deviceError()).toContain('conexão');
  });
  it('does not let a late restore overwrite a newer edit or publish after disposal', async () => {
    let resolve!: (response: Response) => void;
    request.mockImplementationOnce(
      () =>
        new Promise<Response>((r) => {
          resolve = r;
        }),
    );
    const loading = settings.refresh();
    settings.setReference(445);
    resolve(Response.json(saved));
    await loading;
    expect(settings.referenceHz()).toBe(445);
    settings.ngOnDestroy();
    request.mockResolvedValue(Response.json([]));
    await settings.refresh();
    expect(settings.devices()).toEqual(devices);
  });
});
