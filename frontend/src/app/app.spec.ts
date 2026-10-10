import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { LOCAL_FETCH, SESSION_TOKEN } from './features/tuner/tuner-session';

describe('ARPEON shell', () => {
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
    expect(element.textContent).toContain('Abra uma partitura');
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
    expect(element.textContent).toContain('Abra uma partitura');
    expect(request.mock.calls.some(([path]) => path === '/api/tuner/start')).toBe(false);
    const menu = element.querySelector<HTMLDetailsElement>('.menu');
    const summary = menu?.querySelector('summary');
    summary?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    expect(menu?.open).toBe(true);
    summary?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(menu?.open).toBe(false);

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
