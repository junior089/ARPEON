import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DesktopPreferences, UiPreferences } from './desktop-preferences';
import { LOCAL_FETCH, SESSION_TOKEN } from './local-session';

describe('DesktopPreferences', () => {
  const fetcher = vi.fn<typeof fetch>();
  let preferences: DesktopPreferences;
  const saved: UiPreferences = {
    schemaVersion: 1,
    theme: 'light',
    explorer: false,
    inspector: false,
    inspectorWidth: 360,
    zoom: 125,
    mode: 'play',
    scoreDark: true,
  };

  beforeEach(() => {
    localStorage.clear();
    fetcher.mockReset();
    TestBed.configureTestingModule({
      providers: [
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'session' },
      ],
    });
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('restores durable settings even when the new port has no browser storage', async () => {
    fetcher.mockResolvedValueOnce(Response.json(saved));
    preferences = TestBed.inject(DesktopPreferences);
    await preferences.load();
    expect(preferences.snapshot()).toEqual(saved);
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({ 'X-Arpeon-Session': 'session' });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('migrates legacy settings once and gives an existing durable file precedence', async () => {
    localStorage.setItem('arpeon.theme.v1', 'light');
    localStorage.setItem('arpeon.layout.v1', JSON.stringify({ zoom: 140 }));
    fetcher.mockResolvedValueOnce(Response.json(null)).mockResolvedValueOnce(Response.json(saved));
    preferences = TestBed.inject(DesktopPreferences);
    await preferences.load();
    const migrated = JSON.parse(String(fetcher.mock.calls[1]?.[1]?.body)) as UiPreferences;
    expect(migrated.theme).toBe('light');
    expect(migrated.zoom).toBe(140);
    expect(fetcher.mock.calls[1]?.[1]?.method).toBe('PUT');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'new-session' },
      ],
    });
    fetcher.mockResolvedValueOnce(Response.json(saved));
    await TestBed.inject(DesktopPreferences).load();
    expect(TestBed.inject(DesktopPreferences).snapshot().zoom).toBe(125);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it('serializes overlapping updates and retries a failed write without losing the latest value', async () => {
    fetcher.mockResolvedValueOnce(Response.json(null));
    preferences = TestBed.inject(DesktopPreferences);
    await preferences.load();
    let complete!: (response: Response) => void;
    fetcher
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            complete = resolve;
          }),
      )
      .mockResolvedValueOnce(Response.json({}, { status: 500 }));
    preferences.update({ theme: 'light' });
    const first = preferences.flush();
    preferences.update({ zoom: 150 });
    complete(Response.json(saved));
    await first;
    expect(preferences.error()).toContain('guardar os ajustes');
    expect(preferences.snapshot().zoom).toBe(150);
    fetcher.mockResolvedValueOnce(Response.json(saved));
    await preferences.retry();
    const last = JSON.parse(String(fetcher.mock.lastCall?.[1]?.body)) as UiPreferences;
    expect(last.theme).toBe('light');
    expect(last.zoom).toBe(150);
    expect(preferences.error()).toBe('');
    expect(preferences.saving()).toBe(false);
  });

  it('keeps startup usable after malformed data and when browser storage is blocked', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    fetcher.mockResolvedValueOnce(Response.json({ ...saved, zoom: 'huge' }));
    preferences = TestBed.inject(DesktopPreferences);
    await preferences.load();
    expect(preferences.snapshot().zoom).toBe(100);
    expect(preferences.error()).toContain('recuperar');
    fetcher.mockResolvedValueOnce(Response.json(saved));
    preferences.update({ theme: 'light' });
    await preferences.flush();
    expect(preferences.error()).toBe('');
  });
});
