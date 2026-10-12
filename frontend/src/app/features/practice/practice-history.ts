import {
  afterNextRender,
  AfterRenderRef,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  LOCALE_ID,
  OnDestroy,
  signal,
  viewChild,
} from '@angular/core';
import { DatePipe, DecimalPipe, registerLocaleData } from '@angular/common';
import portuguese from '@angular/common/locales/pt';
import { PracticeSession } from './practice-session';

registerLocaleData(portuguese);

export const PRACTICE_KINDS = [
  { id: 'NOTE', name: 'Reconhecer nota' },
  { id: 'LONG_TONE', name: 'Nota longa' },
  { id: 'SCALE', name: 'Escala' },
  { id: 'ARPEGGIO', name: 'Arpejo' },
  { id: 'RHYTHM', name: 'Ritmo básico' },
] as const;

// Native date inputs describe local calendar days, including timezone/DST boundaries.
export function localDayBoundary(value: string, nextDay = false): string {
  if (!value) return '';
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) throw new Error('Escolha datas válidas para o período.');
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(0);
  date.setFullYear(year, month - 1, day);
  date.setHours(0, 0, 0, 0);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day)
    throw new Error('Escolha datas válidas para o período.');
  if (nextDay) date.setDate(date.getDate() + 1);
  return date.toISOString();
}

@Component({
  selector: 'app-practice-history',
  imports: [DatePipe, DecimalPipe],
  providers: [{ provide: LOCALE_ID, useValue: 'pt-BR' }],
  templateUrl: './practice-history.html',
  styleUrl: './practice-history.scss',
})
export class PracticeHistory implements OnDestroy {
  readonly session = inject(PracticeSession);
  private readonly injector = inject(Injector);
  private readonly previousPage = viewChild<ElementRef<HTMLButtonElement>>('previousPage');
  private readonly nextPage = viewChild<ElementRef<HTMLButtonElement>>('nextPage');
  private readonly pageStatus = viewChild<ElementRef<HTMLParagraphElement>>('pageStatus');
  private readonly pagePosition = viewChild<ElementRef<HTMLSpanElement>>('pagePosition');
  private readonly historyRetry = viewChild<ElementRef<HTMLButtonElement>>('historyRetry');
  private focusRender?: AfterRenderRef;
  private disposed = false;
  readonly kinds = PRACTICE_KINDS;
  readonly validationError = signal('');
  readonly pageCount = computed(() => {
    const page = this.session.historyPage();
    return page ? Math.max(1, Math.ceil(page.matchingCount / page.pageSize)) : 1;
  });
  kind = this.session.historyFilters().kind;
  outcome = this.session.historyFilters().outcome;
  fromDate = '';
  untilDate = '';

  text(event: Event): string {
    const target = event.target;
    return target instanceof HTMLSelectElement || target instanceof HTMLInputElement
      ? target.value
      : '';
  }

  kindName(kind: string): string {
    return this.kinds.find((candidate) => candidate.id === kind)?.name ?? kind;
  }

  keyName(key: string): string {
    const names: Record<string, string> = {
      C_MAJOR: 'Dó maior',
      G_MAJOR: 'Sol maior',
      F_MAJOR: 'Fá maior',
      D_MAJOR: 'Ré maior',
      A_MINOR: 'Lá menor',
      E_MINOR: 'Mi menor',
      D_MINOR: 'Ré menor',
    };
    return names[key] ?? key;
  }

  async apply(event?: Event): Promise<void> {
    event?.preventDefault();
    this.validationError.set('');
    try {
      if (this.fromDate && this.untilDate && this.fromDate > this.untilDate)
        throw new Error('A data inicial deve ser anterior ou igual à data final.');
      await this.session.loadHistory(0, {
        kind: this.kind,
        outcome: this.outcome,
        from: localDayBoundary(this.fromDate),
        until: localDayBoundary(this.untilDate, true),
      });
    } catch (failure: unknown) {
      this.validationError.set(failure instanceof Error ? failure.message : 'Período inválido.');
    }
  }

  async clear(): Promise<void> {
    this.kind = this.outcome = this.fromDate = this.untilDate = '';
    await this.apply();
  }

  async navigate(direction: 'previous' | 'next'): Promise<void> {
    const page = this.session.historyPage();
    if (!page || this.session.historyBusy()) return;
    const button = direction === 'previous' ? this.previousPage() : this.nextPage();
    const restoreFocus = document.activeElement === button?.nativeElement;
    await this.session.loadHistory(page.page + (direction === 'previous' ? -1 : 1));
    this.restoreHistoryFocus(direction, restoreFocus);
  }

  async retry(): Promise<void> {
    const restoreFocus = document.activeElement === this.historyRetry()?.nativeElement;
    await this.session.retryHistory();
    this.restoreHistoryFocus('retry', restoreFocus);
  }

  private restoreHistoryFocus(direction: 'previous' | 'next' | 'retry', restore: boolean): void {
    if (!restore || this.disposed) return;
    this.focusRender?.destroy();
    this.focusRender = afterNextRender(
      () => {
        // A user who moved to another control while waiting keeps that focus.
        if (
          this.disposed ||
          (document.activeElement !== document.body &&
            document.activeElement !== document.documentElement)
        )
          return;
        if (this.session.historyError()) {
          this.historyRetry()?.nativeElement.focus();
          return;
        }
        const button =
          direction === 'previous'
            ? this.previousPage()
            : direction === 'next'
              ? this.nextPage()
              : undefined;
        if (button && !button.nativeElement.disabled) button.nativeElement.focus();
        else (this.pagePosition()?.nativeElement ?? this.pageStatus()?.nativeElement)?.focus();
      },
      { injector: this.injector },
    );
  }

  ngOnDestroy(): void {
    this.disposed = true;
    this.focusRender?.destroy();
  }
}
