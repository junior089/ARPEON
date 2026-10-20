import { DatePipe } from '@angular/common';
import {
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  signal,
  untracked,
  ViewChild,
} from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { EditionDraft, EditionDrafts } from '../../core/edition-drafts';
import {
  editionReference,
  editionText,
  editionUuid,
  EditionView,
  isEditionView,
} from './edition-contract';

@Component({
  selector: 'app-edition-panel',
  imports: [DatePipe],
  templateUrl: './edition-panel.html',
  styleUrl: './edition-panel.scss',
})
export class EditionPanel {
  readonly projectId = input.required<string>();
  readonly sourceAssetId = input.required<string>();
  readonly workId = input.required<string>();
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  private readonly drafts = inject(EditionDrafts);
  readonly view = signal<EditionView | null>(null);
  readonly error = signal('');
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly message = signal('');
  readonly mode = signal<'identify' | 'withdraw' | null>(null);
  readonly title = signal('');
  readonly citation = signal('');
  readonly referenceUrl = signal('');
  private baseline = { title: '', citation: '', referenceUrl: '' };
  private controller?: AbortController;
  readonly latest = computed(() => this.view()?.history.at(-1));
  readonly full = computed(() => (this.view()?.history.length ?? 0) >= 16);
  readonly valid = computed(
    () =>
      editionText(this.title(), 200, this.mode() === 'identify') &&
      editionText(this.citation(), 1000, true) &&
      editionText(this.referenceUrl(), 1000) &&
      editionReference(this.referenceUrl().trim()),
  );
  @ViewChild('titleInput') set titleInput(value: ElementRef<HTMLInputElement> | undefined) {
    value?.nativeElement.focus();
  }
  @ViewChild('citationInput') set citationInput(value: ElementRef<HTMLInputElement> | undefined) {
    if (this.mode() === 'withdraw') value?.nativeElement.focus();
  }
  constructor() {
    effect((cleanup) => {
      const id = this.projectId(),
        asset = this.sourceAssetId(),
        work = this.workId();
      const controller = new AbortController();
      this.controller = controller;
      cleanup(() => controller.abort());
      untracked(() => {
        this.view.set(null);
        this.error.set('');
        this.message.set('');
        this.busy.set(false);
        this.loading.set(false);
        this.mode.set(null);
        const draft = this.drafts.get(id);
        if (draft) {
          this.mode.set(draft.mode);
          this.title.set(draft.title);
          this.citation.set(draft.citation);
          this.referenceUrl.set(draft.referenceUrl);
          this.baseline = draft.baseline;
        }
      });
      if (editionUuid(id)) void this.load(id, asset, work, controller.signal);
      else this.error.set('Identificador de projeto inválido.');
    });
  }
  private async load(id: string, asset: string, work: string, signal: AbortSignal): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const response = await this.request(`/api/projects/${id}/editions`, signal);
      const data: unknown = await response.json();
      if (!response.ok || !isEditionView(data, asset, work))
        throw new Error('Não foi possível consultar o histórico da edição. Tente novamente.');
      if (!signal.aborted) this.view.set(data);
    } catch (failure) {
      if (!signal.aborted)
        this.error.set(failure instanceof Error ? failure.message : 'Falha ao consultar a edição.');
    } finally {
      if (!signal.aborted) this.loading.set(false);
    }
  }
  reload(): void {
    if (!this.busy() && this.controller)
      void this.load(this.projectId(), this.sourceAssetId(), this.workId(), this.controller.signal);
  }
  begin(mode: 'identify' | 'withdraw'): void {
    this.mode.set(mode);
    this.error.set('');
    this.message.set('');
    const latest = this.latest();
    this.baseline = {
      title: mode === 'identify' ? (latest?.edition?.title ?? '') : '',
      citation: '',
      referenceUrl: mode === 'identify' ? (latest?.referenceUrl ?? '') : '',
    };
    this.title.set(this.baseline.title);
    this.citation.set('');
    this.referenceUrl.set(this.baseline.referenceUrl);
  }
  change(field: 'title' | 'citation' | 'referenceUrl', value: string): void {
    this[field].set(value);
    const mode = this.mode();
    if (!mode) return;
    const draft: EditionDraft = {
      mode,
      title: this.title(),
      citation: this.citation(),
      referenceUrl: this.referenceUrl(),
      baseline: this.baseline,
    };
    if (
      draft.title === this.baseline.title &&
      draft.citation === this.baseline.citation &&
      draft.referenceUrl === this.baseline.referenceUrl
    )
      this.drafts.clear(this.projectId());
    else this.drafts.put(this.projectId(), draft);
  }
  cancel(): void {
    this.mode.set(null);
    this.drafts.clear(this.projectId());
    this.error.set('');
  }
  async save(event: Event): Promise<void> {
    event.preventDefault();
    const previous = this.view(),
      signal = this.controller?.signal;
    if (
      !previous ||
      !signal ||
      signal.aborted ||
      this.busy() ||
      !this.valid() ||
      this.full() ||
      !this.mode()
    )
      return;
    const title = this.mode() === 'withdraw' ? '' : this.title().normalize('NFC').trim();
    const citation = this.citation().normalize('NFC').trim(),
      referenceUrl = this.referenceUrl().normalize('NFC').trim();
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    try {
      const response = await this.request(`/api/projects/${this.projectId()}/editions`, signal, {
        sourceAssetId: this.sourceAssetId(),
        expectedRevision: previous.history.length,
        title,
        citation,
        referenceUrl,
      });
      const data: unknown = await response.json();
      if (!response.ok)
        throw new Error(
          typeof data === 'object' && data && 'error' in data && typeof data.error === 'string'
            ? data.error
            : 'Não foi possível guardar a identificação.',
        );
      if (
        !isEditionView(data, this.sourceAssetId(), this.workId()) ||
        data.history.length !== previous.history.length + 1 ||
        JSON.stringify(data.history.slice(0, -1)) !== JSON.stringify(previous.history) ||
        (data.history.at(-1)?.edition?.title ?? '') !== title ||
        data.history.at(-1)?.citation !== citation ||
        data.history.at(-1)?.referenceUrl !== referenceUrl
      )
        throw new Error(
          'O registro da edição não foi confirmado. Consulte o histórico antes de tentar novamente.',
        );
      if (!signal.aborted) {
        this.view.set(data);
        this.mode.set(null);
        this.drafts.clear(this.projectId());
        this.message.set(
          title
            ? 'Identificação registrada. A origem e as notas foram preservadas.'
            : 'Identificação retirada. O histórico foi preservado.',
        );
      }
    } catch (failure) {
      if (!signal.aborted)
        this.error.set(
          failure instanceof Error ? failure.message : 'Falha ao guardar a identificação.',
        );
    } finally {
      if (!signal.aborted) this.busy.set(false);
    }
  }
  private request(path: string, signal: AbortSignal, body?: object): Promise<Response> {
    if (!this.token) return Promise.reject(new Error('Abra esta janela pelo aplicativo ARPEON.'));
    return this.fetch(path, {
      method: body ? 'POST' : 'GET',
      headers: {
        'X-Arpeon-Session': this.token,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      credentials: 'omit',
      cache: 'no-store',
      signal: AbortSignal.any([signal, AbortSignal.timeout(30000)]),
    });
  }
}
