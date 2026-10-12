import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PracticeHistory, localDayBoundary } from './practice-history';
import { PracticeSession } from './practice-session';
import { PracticeHistoryPage, PracticeSummary } from './practice-contract';

describe('Practice history filters', () => {
  afterEach(() => TestBed.resetTestingModule());

  function create() {
    const session = {
      historyPage: signal<PracticeHistoryPage | null>(null),
      historyBusy: signal(false),
      historyError: signal(''),
      savedAttempt: signal(false),
      historyFilters: signal({ kind: '', outcome: '', from: '', until: '' }),
      loadHistory: vi.fn().mockResolvedValue(true),
      retryHistory: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [{ provide: PracticeSession, useValue: session }],
    });
    const fixture = TestBed.createComponent(PracticeHistory);
    fixture.detectChanges();
    return { fixture, session, component: fixture.componentInstance };
  }

  it('includes the entire final local calendar day and sends selected filters', async () => {
    const { component, session } = create();
    component.kind = 'RHYTHM';
    component.outcome = 'completed';
    component.fromDate = component.untilDate = '2026-10-10';
    await component.apply();
    const filters = session.loadHistory.mock.calls[0]?.[1];
    expect(filters.kind).toBe('RHYTHM');
    expect(filters.outcome).toBe('completed');
    const from = new Date(filters.from);
    const until = new Date(filters.until);
    expect([from.getFullYear(), from.getMonth(), from.getDate(), from.getHours()]).toEqual([
      2026, 9, 10, 0,
    ]);
    expect([until.getFullYear(), until.getMonth(), until.getDate(), until.getHours()]).toEqual([
      2026, 9, 11, 0,
    ]);
  });

  it('blocks a reversed period without requesting or clearing the stored history', async () => {
    const { component, session, fixture } = create();
    component.fromDate = '2026-10-11';
    component.untilDate = '2026-10-10';
    await component.apply();
    fixture.detectChanges();
    expect(session.loadHistory).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'anterior ou igual',
    );
  });

  it('clears all filters and restarts on the first page', async () => {
    const { component, session } = create();
    component.kind = 'NOTE';
    component.untilDate = '2026-10-10';
    await component.clear();
    expect(session.loadHistory).toHaveBeenCalledWith(0, {
      kind: '',
      outcome: '',
      from: '',
      until: '',
    });
    expect(component.untilDate).toBe('');
  });

  it('rejects impossible dates instead of silently moving to another month', () => {
    expect(() => localDayBoundary('2026-02-30')).toThrow('datas válidas');
    expect(() => localDayBoundary('2026-13-01')).toThrow('datas válidas');
    expect(() => localDayBoundary('10/10/2026')).toThrow('datas válidas');
    expect(localDayBoundary('')).toBe('');
    const end = new Date(localDayBoundary('2026-12-31', true));
    expect([end.getFullYear(), end.getMonth(), end.getDate()]).toEqual([2027, 0, 1]);
  });

  const entry: PracticeSummary = {
    id: 'test-1',
    kind: 'LONG_TONE',
    keyId: 'C_MAJOR',
    startedAt: '2026-10-10T12:00:00Z',
    endedAt: '2026-10-10T12:01:00Z',
    completed: true,
    targets: 1,
    reached: 1,
    heldMs: 3000,
    stabilityCents: 8.5,
    interruptions: 0,
    bpm: 80,
  };
  const page: PracticeHistoryPage = {
    entries: [entry],
    page: 0,
    pageSize: 20,
    matchingCount: 41,
    totalCount: 41,
    completedCount: 41,
  };

  it('keeps keyboard focus on the next control after a page reload', async () => {
    const { component, session, fixture } = create();
    session.historyPage.set(page);
    fixture.detectChanges();
    const next = fixture.nativeElement.querySelector(
      '.history-pagination button:last-child',
    ) as HTMLButtonElement;
    next.focus();
    session.loadHistory.mockImplementation(async () => {
      session.historyPage.set(null);
      session.historyBusy.set(true);
      fixture.detectChanges();
      await Promise.resolve();
      session.historyPage.set({ ...page, page: 1 });
      session.historyBusy.set(false);
      return true;
    });
    await component.navigate('next');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(
      fixture.nativeElement.querySelector('.history-pagination button:last-child'),
    );
    expect(session.loadHistory).toHaveBeenCalledWith(1);
    expect(fixture.nativeElement.textContent).toContain('3.000 ms');
    expect(fixture.nativeElement.textContent).toContain('8,5 cents');
  });

  it('focuses the page position at the final boundary and retry after a failed page', async () => {
    const { component, session, fixture } = create();
    session.historyPage.set({ ...page, page: 1 });
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '.history-pagination button:last-child',
      ) as HTMLButtonElement
    ).focus();
    session.loadHistory.mockImplementation(async () => {
      session.historyPage.set(null);
      fixture.detectChanges();
      await Promise.resolve();
      session.historyPage.set({ ...page, page: 2 });
      return true;
    });
    await component.navigate('next');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement?.textContent).toContain('Página 3 de 3');
    (
      fixture.nativeElement.querySelector(
        '.history-pagination button:first-child',
      ) as HTMLButtonElement
    ).focus();
    session.loadHistory.mockImplementation(async () => {
      session.historyPage.set(null);
      fixture.detectChanges();
      await Promise.resolve();
      session.historyError.set('Histórico indisponível.');
      return false;
    });
    await component.navigate('previous');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement?.textContent).toContain('Tentar carregar novamente');
  });
});
