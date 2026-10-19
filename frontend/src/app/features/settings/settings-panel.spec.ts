import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DesktopPreferences, UiPreferences } from '../../core/desktop-preferences';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { AudioSettings } from '../../core/audio-settings';
import { ScoreReadingPreferences } from '../../core/score-reading-preferences';
import { SettingsPanel } from './settings-panel';

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) throw new Error('Required settings control missing');
  return value;
}

describe('settings center', () => {
  let saved: UiPreferences | null;
  const request = vi.fn<typeof fetch>();

  async function open() {
    await TestBed.configureTestingModule({
      imports: [SettingsPanel],
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'session' },
      ],
    }).compileComponents();
    await TestBed.inject(DesktopPreferences).load();
    const fixture = TestBed.createComponent(SettingsPanel);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    localStorage.clear();
    saved = null;
    request.mockReset();
    request.mockImplementation(async (path, init) => {
      if (path === '/api/preferences') {
        if (init?.method === 'PUT') saved = JSON.parse(String(init.body)) as UiPreferences;
        return Response.json(saved);
      }
      return Response.json(path === '/api/audio/preferences' ? null : []);
    });
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.clear();
    delete document.documentElement.dataset['theme'];
  });

  it('searches real categories without accents and exposes an empty state', async () => {
    const fixture = await open();
    const panel = fixture.componentInstance;
    const element = fixture.nativeElement as HTMLElement;
    const search = required(element.querySelector<HTMLInputElement>('#settings-search'));
    search.value = 'AFINACAO';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(panel.visible().map((item) => item.id)).toEqual(['audio']);
    expect(element.querySelector('#settings-audio-input')).not.toBeNull();
    panel.query.set('inexistente');
    fixture.detectChanges();
    expect(element.textContent).toContain('Nenhuma configuração encontrada');
    expect(element.querySelector('section')).toBeNull();
    panel.select('score');
    fixture.detectChanges();
    expect(search.value).toBe('');
    expect(element.querySelector('h3')?.textContent).toBe('Partituras e estudo');
  });

  it('applies interface controls to the shared layout and saves through the authenticated bridge', async () => {
    const fixture = await open();
    const element = fixture.nativeElement as HTMLElement;
    required(
      Array.from(element.querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Claro',
      ),
    ).click();
    required(element.querySelector<HTMLInputElement>('input[type="checkbox"]')).click();
    const zoom = required(element.querySelector<HTMLInputElement>('input[type="number"]'));
    zoom.value = '125';
    zoom.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const panel = fixture.componentInstance;
    expect(document.documentElement.dataset['theme']).toBe('light');
    expect(panel.layout.explorer()).toBe(false);
    expect(panel.layout.zoom()).toBe(125);
    await panel.preferences.flush();
    expect(saved).toMatchObject({ theme: 'light', explorer: false, zoom: 125 });
    expect(request).toHaveBeenCalledWith(
      '/api/preferences',
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ 'X-Arpeon-Session': 'session' }),
      }),
    );
  });

  it('finds visible control labels, plural forms and combined terms', async () => {
    const fixture = await open();
    const panel = fixture.componentInstance;
    for (const [query, category] of [
      ['papel', 'score'],
      ['nomes', 'score'],
      ['oitavas', 'score'],
      ['papel escuro', 'score'],
      ['NOMES DAS NOTAS', 'score'],
      ['explorador de partes', 'interface'],
      ['referencia', 'audio'],
      ['verificações automáticas', 'updates'],
    ] as const) {
      panel.query.set(query);
      expect(panel.results().map((item) => item.id)).toContain(category);
    }
  });

  it('shares reading controls with the editor and restores them after a new application session', async () => {
    const fixture = await open();
    const panel = fixture.componentInstance;
    panel.select('score');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const checkboxes = element.querySelectorAll<HTMLInputElement>('input[type="checkbox"]');
    required(checkboxes[1]).click();
    const selects = element.querySelectorAll('select');
    const language = required(selects[0]);
    language.value = 'portuguese';
    language.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(TestBed.inject(ScoreReadingPreferences).readingEnabled()).toBe(true);
    expect(TestBed.inject(ScoreReadingPreferences).readingLanguage()).toBe('portuguese');
    await panel.preferences.flush();
    fixture.destroy();
    TestBed.resetTestingModule();
    localStorage.clear();
    const reopened = await open();
    expect(reopened.componentInstance.reading.readingEnabled()).toBe(true);
    expect(reopened.componentInstance.reading.readingLanguage()).toBe('portuguese');
  });

  it('resets one category while preserving unrelated preferences and device choices', async () => {
    const fixture = await open();
    const panel = fixture.componentInstance;
    panel.appearance.setTheme('light');
    panel.layout.setZoom(140);
    panel.layout.toggleScoreTheme();
    panel.reading.readingEnabled.set(true);
    TestBed.inject(AudioSettings).setReference(442);
    panel.reset('interface');
    fixture.detectChanges();
    expect(panel.layout.zoom()).toBe(100);
    expect(panel.layout.scoreDark()).toBe(true);
    expect(panel.reading.readingEnabled()).toBe(true);
    panel.appearance.setTheme('light');
    panel.reset('score');
    fixture.detectChanges();
    expect(panel.layout.scoreDark()).toBe(false);
    expect(panel.reading.readingEnabled()).toBe(false);
    expect(panel.appearance.theme()).toBe('light');
    expect(TestBed.inject(AudioSettings).referenceHz()).toBe(442);
    await panel.preferences.flush();
    expect(saved).toMatchObject({
      theme: 'light',
      zoom: 100,
      readingEnabled: false,
      scoreDark: false,
    });
  });

  it('locks audio controls during capture and leaves microphone activation and update consent explicit', async () => {
    const fixture = await open();
    fixture.componentRef.setInput('captureActive', true);
    const panel = fixture.componentInstance;
    panel.select('audio');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector<HTMLInputElement>('input[type="number"]')?.disabled).toBe(true);
    expect(element.querySelector<HTMLSelectElement>('select')?.disabled).toBe(true);
    const manage = vi.fn();
    panel.manageUpdates.subscribe(manage);
    panel.select('updates');
    fixture.detectChanges();
    required(
      Array.from(element.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Gerenciar atualizações'),
      ),
    ).click();
    expect(manage).toHaveBeenCalledOnce();
    expect(
      request.mock.calls.some(
        ([path]) =>
          String(path).includes('/start') || String(path).includes('/updates/preferences'),
      ),
    ).toBe(false);
  });
});
