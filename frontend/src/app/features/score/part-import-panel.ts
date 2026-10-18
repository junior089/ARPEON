import { Component, inject, signal } from '@angular/core';
import { PartImportReview } from '../../core/score-contract';
import { ScoreSession } from './score-session';

@Component({
  selector: 'app-part-import-panel',
  templateUrl: './part-import-panel.html',
  styleUrl: './part-import-panel.scss',
})
export class PartImportPanel {
  readonly session = inject(ScoreSession);
  readonly review = signal<PartImportReview | null>(null);
  readonly selected = signal<string[]>([]);
  readonly replaceId = signal('');
  readonly manual = signal(false);
  readonly anchors = signal([{ sourceMeasure: 0, compositionMeasure: 0 }]);
  private queue: File[] = [];

  async open(event: Event): Promise<void> {
    if (!(event.target instanceof HTMLInputElement)) return;
    this.queue = Array.from(event.target.files ?? []);
    event.target.value = '';
    await this.next();
  }

  private async next(): Promise<void> {
    const file = this.queue.shift();
    if (!file) {
      this.review.set(null);
      return;
    }
    const result = await this.session.stageParts(file);
    this.review.set(result);
    this.selected.set(result?.parts.map((part) => part.id) ?? []);
    this.manual.set(false);
    this.replaceId.set('');
    this.anchors.set([{ sourceMeasure: 0, compositionMeasure: 0 }]);
    if (!result) this.queue = [];
  }

  select(id: string, event: Event): void {
    if (!(event.target instanceof HTMLInputElement)) return;
    this.selected.update((ids) =>
      ids.includes(id) ? ids.filter((item) => item !== id) : [...ids, id],
    );
  }

  setReplacement(event: Event): void {
    if (event.target instanceof HTMLSelectElement) this.replaceId.set(event.target.value);
  }

  setManual(event: Event): void {
    if (event.target instanceof HTMLInputElement) this.manual.set(event.target.checked);
  }

  setAnchor(index: number, field: 'sourceMeasure' | 'compositionMeasure', event: Event): void {
    if (!(event.target instanceof HTMLInputElement)) return;
    const value = event.target.valueAsNumber - 1;
    this.anchors.update((anchors) =>
      anchors.map((anchor, i) => (i === index ? { ...anchor, [field]: value } : anchor)),
    );
  }

  addAnchor(): void {
    const previous = this.anchors().at(-1);
    if (!previous || this.anchors().length >= 64) return;
    this.anchors.update((anchors) => [
      ...anchors,
      {
        sourceMeasure: previous.sourceMeasure + 1,
        compositionMeasure: previous.compositionMeasure + 1,
      },
    ]);
  }

  removeAnchor(index: number): void {
    this.anchors.update((anchors) => anchors.filter((_, i) => i !== index));
  }

  canConfirm(): boolean {
    const review = this.review();
    return (
      !!review &&
      this.selected().length > 0 &&
      (!this.replaceId() || this.selected().length === 1) &&
      (this.manual() ||
        review.parts.filter((p) => this.selected().includes(p.id)).every((p) => p.compatible))
    );
  }

  async confirm(): Promise<void> {
    const review = this.review();
    if (!review || !this.canConfirm()) return;
    const accepted = await this.session.edit('commit-import', {
      token: review.token,
      partIds: JSON.stringify(this.selected()),
      replaceId: this.replaceId(),
      anchors: JSON.stringify(
        this.manual() ? this.anchors() : [{ sourceMeasure: 0, compositionMeasure: 0 }],
      ),
      manual: this.manual(),
    });
    if (accepted) await this.next();
  }

  async cancel(): Promise<void> {
    this.queue = [];
    const review = this.review();
    if (!review || (await this.session.edit('cancel-import', { token: review.token })))
      this.review.set(null);
  }
}
