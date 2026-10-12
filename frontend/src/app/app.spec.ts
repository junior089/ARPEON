import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { LOCAL_FETCH, SESSION_TOKEN } from './features/tuner/tuner-session';

describe('ARPEON shell', () => {
  it('replaces a failed check message with a newer native result without hiding a failed preference save', async () => {
    let state = 'idle';
    let lastCheckedAt = '';
    const request = vi.fn<typeof fetch>().mockImplementation(async (path) => {
      if (path === '/api/updates/check')
        return Response.json({ error: 'Conexão interrompida.' }, { status: 500 });
      if (path === '/api/updates/preferences')
        return Response.json({ error: 'Storage unavailable' }, { status: 500 });
      if (path === '/api/updates')
        return Response.json({
          enabled: true,
          version: '0.1.0',
          message: 'Atualizado',
          automaticChecking: false,
          state,
          lastCheckedAt,
        });
      return Response.json([]);
    });
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'test' },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.componentInstance.loadUpdateStatus();
    await fixture.componentInstance.checkUpdates();
    expect(fixture.componentInstance.updateMessage()).toBe('Conexão interrompida.');
    await fixture.componentInstance.setAutomaticUpdates(true);
    state = 'current';
    lastCheckedAt = new Date(Date.now() + 1000).toISOString();
    await fixture.componentInstance.loadUpdateStatus();
    expect(fixture.componentInstance.updateMessage()).toBe('');
    expect(fixture.componentInstance.updatePreferenceError()).toContain('Tente novamente');
    fixture.destroy();
  });

  it('loads update consent and persists changes through the authenticated desktop API', async () => {
    let automaticChecking = false;
    const request = vi.fn<typeof fetch>().mockImplementation(async (path, options) => {
      if (path === '/api/updates/preferences') {
        const body = JSON.parse(String(options?.body)) as { automaticChecking: boolean };
        automaticChecking = body.automaticChecking;
      }
      if (String(path).startsWith('/api/updates'))
        return Response.json({
          enabled: true,
          version: '0.1.0',
          channel: 'testing',
          message: 'Pronto',
          state: 'idle',
          automaticChecking,
          lastCheckedAt: '',
        });
      return Response.json([]);
    });
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'test' },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.componentInstance.loadUpdateStatus();
    expect(fixture.componentInstance.automaticUpdates()).toBe(false);
    await fixture.componentInstance.setAutomaticUpdates(true);
    expect(fixture.componentInstance.automaticUpdates()).toBe(true);
    expect(request).toHaveBeenCalledWith(
      '/api/updates/preferences',
      expect.objectContaining({
        method: 'PUT',
        body: '{"automaticChecking":true}',
        headers: expect.objectContaining({ 'X-Arpeon-Session': 'test' }),
      }),
    );
    fixture.destroy();
  });

  it('keeps update consent unchanged when saving fails', async () => {
    const request = vi.fn<typeof fetch>().mockImplementation(async (path) =>
      path === '/api/updates/preferences'
        ? Response.json({ error: 'Storage unavailable' }, { status: 500 })
        : path === '/api/updates'
          ? Response.json({
              enabled: true,
              version: '0.1.0',
              message: 'Pronto',
              automaticChecking: false,
            })
          : Response.json([]),
    );
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'test' },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.componentInstance.loadUpdateStatus();
    await fixture.componentInstance.setAutomaticUpdates(true);
    expect(fixture.componentInstance.automaticUpdates()).toBe(false);
    expect(fixture.componentInstance.updatePreferenceError()).toContain('Tente novamente');
    fixture.destroy();
  });

  it('opens the score workspace and keeps the tuner available without a score', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockImplementation(async (input) =>
        input === '/api/practice'
          ? Response.json({ error: 'Create an exercise first' }, { status: 409 })
          : Response.json([]),
      );
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'test' },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Abra ou arraste um arquivo');
    expect(element.textContent).toContain('Abrir partitura');
    expect(element.textContent).toContain('Reprodução disponível ao abrir');
    const tuner = Array.from(element.querySelectorAll<HTMLButtonElement>('nav button')).find((b) =>
      b.textContent?.includes('Afinador'),
    );
    tuner?.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(element.textContent).toContain('Ligar microfone');
    });
    expect(element.textContent).toContain('Afinador cromático');
    expect(element.querySelector('select')?.getAttribute('id')).toBe('audio-input');
    const start = Array.from(element.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Ligar microfone'),
    );
    expect(start?.disabled).toBe(true);
    const practice = Array.from(element.querySelectorAll<HTMLButtonElement>('nav button')).find(
      (b) => b.textContent?.includes('Prática'),
    );
    practice?.click();
    await fixture.whenStable();
    expect(element.textContent).toContain('Gerar exercício');
    expect(element.textContent).toContain('Referência de digitação');
    const score = Array.from(element.querySelectorAll<HTMLButtonElement>('nav button')).find((b) =>
      b.textContent?.includes('Partitura'),
    );
    score?.click();
    await fixture.whenStable();
    expect(element.textContent).toContain('Abra ou arraste um arquivo');
    expect(request.mock.calls.some(([path]) => path === '/api/tuner/start')).toBe(false);
    const menu = element.querySelector<HTMLDetailsElement>('.menu');
    const summary = menu?.querySelector('summary');
    summary?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    expect(menu?.open).toBe(true);
    summary?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(menu?.open).toBe(false);

    const menusToggle = element.querySelector<HTMLButtonElement>('.menus-toggle');
    menusToggle?.click();
    fixture.detectChanges();
    summary?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    fixture.detectChanges();
    expect(fixture.componentInstance.menusOpen()).toBe(true);
    expect(element.querySelectorAll<HTMLDetailsElement>('.menu')[1]?.open).toBe(true);
    menusToggle?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(fixture.componentInstance.menusOpen()).toBe(false);

    const editorInput = element.querySelector('input');
    editorInput?.dispatchEvent(
      new KeyboardEvent('keydown', { key: '4', altKey: true, bubbles: true }),
    );
    expect(fixture.componentInstance.view()).toBe('score');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: '4', altKey: true, bubbles: true }));
    await fixture.whenStable();
    expect(fixture.componentInstance.view()).toBe('tuner');
  });
});
