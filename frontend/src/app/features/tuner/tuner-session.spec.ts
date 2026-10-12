import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN, TunerSession } from './tuner-session';

describe('TunerSession lifecycle', () => {
  const request = vi.fn<typeof fetch>();
  let session: TunerSession;
  const idle = {
    state: 'STARTING',
    frequencyHz: null,
    nearestPitch: null,
    octave: null,
    centsDeviation: null,
    confidence: 0,
    capturedAt: null,
    bufferLatencyMs: 0,
    processingLatencyMs: 0,
    analysisAgeMs: 0,
    droppedFrames: 0,
    inputOverruns: 0,
    sampleRateHz: 0,
    referenceHz: 440,
    message: 'Starting',
  };
  beforeEach(() => {
    request.mockReset();
    TestBed.configureTestingModule({
      providers: [
        TunerSession,
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'test-session' },
      ],
    });
    session = TestBed.inject(TunerSession);
    session.devices.set([{ id: 'a', name: 'Input', sampleRatesHz: [48000] }]);
  });
  afterEach(() => TestBed.resetTestingModule());

  it('discovers inputs without activating capture', async () => {
    request.mockImplementation(async (path) =>
      Response.json(
        path === '/api/audio/preferences'
          ? null
          : [{ id: 'a', name: 'Input', sampleRatesHz: [48000] }],
      ),
    );
    await session.refreshDevices();
    expect(session.selectedDevice()).toBe('a');
    expect(session.active()).toBe(false);
    expect(request).toHaveBeenCalledTimes(2);
    expect(request.mock.calls.map(([path]) => path)).toEqual([
      '/api/audio/preferences',
      '/api/devices',
    ]);
  });
  it('sends explicit consent and clears display on stop', async () => {
    session.selectedDevice.set('a');
    request.mockImplementation(() => Promise.resolve(Response.json(idle)));
    await session.start();
    expect(session.active()).toBe(true);
    const start = request.mock.calls.find(([path]) => path === '/api/tuner/start');
    expect(start?.[1]?.body).toBe(
      JSON.stringify({ deviceId: 'a', referenceHz: 440, consent: true }),
    );
    const owner = new Headers(start?.[1]?.headers).get('X-Arpeon-Capture-Owner');
    expect(owner).toMatch(/^[0-9a-f-]{36}$/);
    await session.stop();
    const stopped = request.mock.calls.find(([path]) => path === '/api/tuner/stop');
    expect(new Headers(stopped?.[1]?.headers).get('X-Arpeon-Capture-Owner')).toBe(owner);
    expect(session.observation()).toBeNull();
    expect(session.active()).toBe(false);
  });
  it('handles denied permission and attempts to release capture', async () => {
    session.selectedDevice.set('a');
    request.mockResolvedValue(new Response('', { status: 409 }));
    await session.start();
    expect(session.active()).toBe(false);
    expect(session.error()).toContain('permissão');
    expect(request.mock.calls.some(([path]) => path === '/api/tuner/stop')).toBe(true);
  });
  it('does not display malformed measurements or keep capture active', async () => {
    session.selectedDevice.set('a');
    request
      .mockResolvedValueOnce(Response.json(idle))
      .mockResolvedValueOnce(Response.json({ state: 'TRACKING' }))
      .mockResolvedValue(Response.json(idle));
    await session.start();
    expect(session.active()).toBe(false);
    expect(session.observation()).toBeNull();
    expect(session.error()).toContain('inválida');
  });
  it('refuses invalid concert reference without an HTTP request', async () => {
    session.selectedDevice.set('a');
    session.referenceHz.set(NaN);
    await session.start();
    expect(request).not.toHaveBeenCalled();
  });
});
