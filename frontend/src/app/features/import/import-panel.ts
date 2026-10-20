import {
  Component,
  computed,
  EventEmitter,
  inject,
  OnDestroy,
  OnInit,
  Output,
  signal,
} from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { ScoreSession } from '../score/score-session';
import { SourceView } from '../../core/score-contract';
import { ImportRouting, importRoute, SCORE_FILE_ACCEPT } from '../../core/import-routing';
import { NotationPreview } from '../../shared/notation-preview';
import { SourceCapabilities } from '../../core/source-capabilities';

interface DraftNote {
  step: string;
  accidental: string;
  octave: number | null;
  onsetTicks: number | null;
  durationTicks: number | null;
  confidence: number;
}
interface Preview {
  token: string;
  source?: SourceView | null;
  draft: {
    kind: string;
    filename: string;
    sha256: string;
    rhythm: string;
    notes: DraftNote[];
    warnings: string[];
  };
}

interface OmrPreview {
  xml?: string;
  token: string;
  title: string;
  filename: string;
  sha256: string;
  pages: number;
  parts: number;
  measures: number;
  recognizedMeasures: { part: string; number: string; events: string[] }[];
  pagesRequiringReview: number[];
  measuresRequiringReview: string[];
  warnings: string[];
  unsupportedSymbols: string[];
}

interface OmrStatus {
  available: boolean;
  recognizing?: boolean;
  recognitionPhase?: string;
  jobId?: string | null;
}

@Component({
  selector: 'app-import-panel',
  imports: [NotationPreview],
  templateUrl: './import-panel.html',
  styleUrl: './import-panel.scss',
})
export class ImportPanel implements OnInit, OnDestroy {
  readonly sources = inject(SourceCapabilities);
  readonly sourceTab = signal<'paste' | 'file' | 'url' | 'omr'>('file');
  readonly fileAccept = SCORE_FILE_ACCEPT;
  private readonly routing = inject(ImportRouting);
  readonly originalZoom = signal(100);
  readonly resultZoom = signal(100);
  readonly dragging = signal(false);
  readonly draftXml = signal('');
  readonly draftNotationError = signal('');
  readonly drawingDraft = signal(false);
  private draftNotationGeneration = 0;
  readonly sourceTabs = [
    { id: 'file', label: 'Arquivo' },
    { id: 'paste', label: 'Colar notas' },
    { id: 'url', label: 'Wikimedia' },
  ] as const;
  @Output() readonly imported = new EventEmitter<void>();
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly session = inject(ScoreSession);
  readonly pending = signal<Preview | null>(null);
  readonly omrPending = signal<OmrPreview | null>(null);
  readonly omrOriginalUrl = signal('');
  readonly omrOriginalIsPdf = signal(false);
  readonly omrStatus = signal<OmrStatus | null>(null);
  readonly recognizingOmr = signal(false);
  readonly cancellingOmr = signal(false);
  readonly omrMessage = signal('');
  readonly omrDiagnostic = signal('');
  readonly omrOriginalPage = signal(1);
  readonly omrPageImage = signal('');
  readonly omrPageLoading = signal(false);
  readonly omrPageError = signal('');
  private omrPdfFile: File | null = null;
  private omrPageGeneration = 0;
  readonly omrDraftWarnings = computed(
    () =>
      this.omrPending()?.warnings.filter((w) => /provisóri|incompleto|Quiálteras/.test(w)) ?? [],
  );
  readonly busy = signal(false);
  readonly error = signal('');
  readonly draftPage = signal(0);
  readonly omrPage = signal(0);
  readonly visibleDraftNotes = computed(
    () =>
      this.pending()?.draft.notes.slice(this.draftPage() * 100, (this.draftPage() + 1) * 100) ?? [],
  );
  readonly visibleOmrMeasures = computed(
    () =>
      this.omrPending()?.recognizedMeasures.slice(this.omrPage() * 25, (this.omrPage() + 1) * 25) ??
      [],
  );
  notation: 'NOTES' | 'SOLFEGE' | 'CIFRA_MELODICA' = 'CIFRA_MELODICA';
  pasted = '';
  sourceUrl = '';
  contact = '';
  fallbackOctave = 4;
  equalNoteTicks = 480;
  acknowledged = false;
  omrAcknowledged = false;
  private omrStatusTimer: number | undefined;
  private omrJobId: string | null = null;
  private disposed = false;

  ngOnInit(): void {
    void this.refreshOmrStatus();
    const file = this.routing.take();
    if (file) void this.importFile(file);
  }

  async chooseFile(event: Event): Promise<void> {
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    input.value = '';
    await this.importFile(file);
  }

  switchSource(tab: 'file' | 'paste' | 'url'): void {
    if (this.busy() || this.sourceTab() === tab) return;
    if (this.omrPending()) {
      void this.cancelOmr();
      this.omrJobId = null;
    }
    this.pending.set(null);
    this.omrPending.set(null);
    this.clearOmrOriginal();
    this.resetDraftNotation();
    this.acknowledged = false;
    this.omrAcknowledged = false;
    this.error.set('');
    this.sourceTab.set(tab);
    if (tab === 'url') void this.sources.load();
  }

  async drop(event: DragEvent): Promise<void> {
    event.preventDefault();
    this.dragging.set(false);
    const files = event.dataTransfer?.files;
    if (!files?.length || this.busy()) return;
    if (files.length !== 1) {
      this.error.set('Importe um arquivo por vez para revisar cada documento.');
      return;
    }
    const file = files[0];
    if (file) await this.importFile(file);
  }

  async importFile(file: File): Promise<void> {
    if (this.busy() || this.session.busy()) return;
    this.sourceTab.set('file');
    this.error.set('');
    const route = importRoute(file.name);
    if (route === 'recognition') {
      await this.recognizeFile(file);
      return;
    }
    if (route === 'draft') {
      await this.analyzeDraftFile(file);
      return;
    }
    if (route === 'unsupported') {
      this.error.set(
        'Formato não suportado. Escolha MusicXML, MXL, MSCZ de uma pauta, PDF, PNG, JPEG, MIDI ou WAV.',
      );
      return;
    }
    if (
      this.session.projectDirty() &&
      !window.confirm('A partitura atual tem alterações não salvas. Quer substituí-la?')
    )
      return;
    this.busy.set(true);
    try {
      if (await this.session.open(file)) this.imported.emit();
      else this.error.set(this.session.error());
    } finally {
      this.busy.set(false);
    }
  }

  async refreshOmrStatus(): Promise<void> {
    if (!this.token) return;
    try {
      const response = await this.fetch('/api/omr/status', {
        headers: { 'X-Arpeon-Session': this.token },
        signal: AbortSignal.timeout(10_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      const result: unknown = await response.json();
      if (!this.disposed && response.ok && isOmrStatus(result)) this.omrStatus.set(result);
    } catch {
      // OMR status is optional until the user chooses its import flow.
    }
  }

  async analyzePaste(): Promise<void> {
    const bytes = new TextEncoder().encode(this.pasted);
    await this.analyze(bytes, 'texto-colado.txt', this.notation);
  }

  textValue(event: Event): string {
    const target = event.target;
    return target instanceof HTMLInputElement ||
      target instanceof HTMLSelectElement ||
      target instanceof HTMLTextAreaElement
      ? target.value
      : '';
  }

  checked(event: Event): boolean {
    return event.target instanceof HTMLInputElement && event.target.checked;
  }

  pageEnd(page: number, pageSize: number, total: number): number {
    return Math.min((page + 1) * pageSize, total);
  }

  async analyzeUrl(): Promise<void> {
    if (this.busy()) return;
    await this.sources.load();
    if (this.busy() || this.disposed) return;
    if (!this.sources.canImportCommons()) {
      this.error.set(
        this.sources.error() || 'A importação Wikimedia por URL não está disponível nesta sessão.',
      );
      return;
    }
    this.draftPage.set(0);
    this.pending.set(null);
    this.omrPending.set(null);
    this.clearOmrOriginal();
    this.acknowledged = false;
    this.busy.set(true);
    this.error.set('');
    try {
      if (!this.token) throw new Error('Abra esta janela pelo aplicativo ARPEON.');
      const response = await this.fetch('/api/import/url', {
        method: 'POST',
        headers: { 'X-Arpeon-Session': this.token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: this.sourceUrl.trim(), contact: this.contact.trim() }),
        signal: AbortSignal.timeout(20_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      const result: unknown = await response.json();
      if (!response.ok) {
        if (
          typeof result === 'object' &&
          result !== null &&
          'error' in result &&
          typeof result.error === 'string'
        )
          throw new Error(result.error);
        throw new Error('Não foi possível obter a fonte.');
      }
      if (!isPreview(result) || !result.source) throw new Error('Prévia da fonte inválida.');
      this.pending.set(result);
    } catch (failure: unknown) {
      this.error.set(failure instanceof Error ? failure.message : 'Falha ao obter a fonte.');
    } finally {
      this.busy.set(false);
    }
  }

  async analyzeFile(event: Event): Promise<void> {
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    input.value = '';
    await this.analyzeDraftFile(file);
  }

  private async analyzeDraftFile(file: File): Promise<void> {
    const kind = /\.midi?$/i.test(file.name) ? 'MIDI' : /\.wav$/i.test(file.name) ? 'WAV' : '';
    if (!kind) {
      this.error.set('Escolha um arquivo .mid, .midi ou .wav.');
      return;
    }
    const max = 32 * 1_048_576;
    if (!file.size || file.size > max) {
      this.error.set('Arquivo fora do limite local de 32 MiB.');
      return;
    }
    await this.analyze(await file.arrayBuffer(), file.name, kind);
  }

  async analyzeOmr(event: Event): Promise<void> {
    if (this.busy()) return;
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    input.value = '';
    await this.recognizeFile(file);
  }

  private async recognizeFile(file: File): Promise<void> {
    this.pending.set(null);
    this.omrPending.set(null);
    this.clearOmrOriginal();
    this.omrAcknowledged = false;
    this.omrPage.set(0);
    if (
      !/\.(pdf|png|jpe?g)$/i.test(file.name) ||
      /[/\\:]/.test(file.name) ||
      [...file.name].some((char) => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127) ||
      file.name.length > 120
    ) {
      this.error.set('Escolha um PDF, PNG ou JPEG com nome de até 120 caracteres, sem caminho.');
      return;
    }
    const max = /\.pdf$/i.test(file.name) ? 64 * 1_048_576 : 32 * 1_048_576;
    if (!file.size || file.size > max) {
      this.error.set(`Arquivo OMR fora do limite de ${max / 1_048_576} MiB.`);
      return;
    }
    this.busy.set(true);
    this.recognizingOmr.set(true);
    this.cancellingOmr.set(false);
    this.omrMessage.set('');
    this.omrDiagnostic.set('');
    const jobId = crypto.randomUUID();
    this.omrJobId = jobId;
    this.error.set('');
    try {
      if (!this.token) throw new Error('Abra esta janela pelo aplicativo ARPEON.');
      const statusResponse = await this.fetch('/api/omr/status', {
        headers: { 'X-Arpeon-Session': this.token },
        signal: AbortSignal.timeout(10_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      const status: unknown = await statusResponse.json();
      if (!statusResponse.ok || !isOmrStatus(status))
        throw new Error('Não foi possível verificar o reconhecimento óptico.');
      this.omrStatus.set(status);
      if (!status.available)
        throw new Error(
          'O reconhecimento óptico não está disponível. Reinstale o pacote completo do ARPEON.',
        );
      this.scheduleOmrStatusPoll();
      const response = await this.fetch('/api/omr/preview', {
        method: 'POST',
        headers: {
          'X-Arpeon-Session': this.token,
          'X-Arpeon-Filename': encodeURIComponent(file.name),
          'X-Arpeon-Job': jobId,
          'Content-Type': 'application/octet-stream',
        },
        body: await file.arrayBuffer(),
        signal: AbortSignal.timeout(750_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      if (this.disposed || this.cancellingOmr()) return;
      const result: unknown = await response.json();
      if (this.disposed || this.cancellingOmr() || this.omrJobId !== jobId) return;
      if (!response.ok) {
        if (
          typeof result === 'object' &&
          result !== null &&
          'error' in result &&
          typeof result.error === 'string'
        )
          throw new Error(result.error);
        throw new Error('Falha no reconhecimento OMR.');
      }
      if (!isOmrPreview(result)) throw new Error('Prévia OMR inválida.');
      this.omrOriginalUrl.set(
        URL.createObjectURL(
          new Blob([file], {
            type: /\.pdf$/i.test(file.name)
              ? 'application/pdf'
              : /\.png$/i.test(file.name)
                ? 'image/png'
                : 'image/jpeg',
          }),
        ),
      );
      this.omrOriginalIsPdf.set(/\.pdf$/i.test(file.name));
      this.omrPending.set(result);
      if (this.omrOriginalIsPdf()) {
        this.omrPdfFile = file;
        void this.showOmrOriginalPage(1);
      }
    } catch (failure: unknown) {
      if (this.disposed || this.cancellingOmr()) return;
      const detail = failure instanceof Error ? failure.message : '';
      const split = detail.indexOf('\n');
      if (split >= 0) this.omrDiagnostic.set(detail.slice(split + 1));
      this.error.set(
        failure instanceof TypeError
          ? 'A conexão com o aplicativo local caiu. Verifique se o ARPEON continua aberto e tente novamente.'
          : failure instanceof Error
            ? split >= 0
              ? detail.slice(0, split)
              : failure.message
            : 'Falha no reconhecimento OMR.',
      );
    } finally {
      this.recognizingOmr.set(false);
      if (this.cancellingOmr())
        this.omrMessage.set('Reconhecimento cancelado. A partitura atual foi preservada.');
      if (this.omrStatusTimer !== undefined) window.clearTimeout(this.omrStatusTimer);
      this.busy.set(false);
    }
  }

  omrRecognitionLabel(): string {
    if (this.cancellingOmr()) return 'Encerrando o reconhecimento e liberando os arquivos…';
    const phase =
      this.omrStatus()?.jobId === this.omrJobId ? this.omrStatus()?.recognitionPhase : 'reading';
    if (phase === 'recognizing') return 'Reconhecendo as páginas no Audiveris…';
    if (phase === 'preparing') return 'Preparando o rascunho e os avisos de revisão…';
    return 'Lendo e validando o arquivo…';
  }

  async showOmrOriginalPage(page: number): Promise<void> {
    const file = this.omrPdfFile;
    const preview = this.omrPending();
    if (!file || !preview || this.omrPageLoading() || page < 1 || page > preview.pages) return;
    const generation = ++this.omrPageGeneration;
    this.omrOriginalPage.set(page);
    this.omrPageLoading.set(true);
    this.omrPageError.set('');
    this.clearOmrPageImage();
    try {
      const response = await this.fetch('/api/omr/pdf-page', {
        method: 'POST',
        headers: {
          'X-Arpeon-Session': this.token,
          'X-Arpeon-Filename': encodeURIComponent(file.name),
          'X-Arpeon-Page': String(page),
          'Content-Type': 'application/octet-stream',
        },
        body: await file.arrayBuffer(),
        signal: AbortSignal.timeout(60_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('image/png'))
        throw new Error(
          'Não foi possível exibir esta página. Tente novamente ou salve uma cópia do original.',
        );
      const blob = await response.blob();
      if (this.disposed || generation !== this.omrPageGeneration) return;
      this.omrPageImage.set(URL.createObjectURL(new Blob([blob], { type: 'image/png' })));
    } catch {
      if (!this.disposed && generation === this.omrPageGeneration)
        this.omrPageError.set(
          'Não foi possível exibir esta página. Tente novamente ou salve uma cópia do original.',
        );
    } finally {
      if (generation === this.omrPageGeneration) this.omrPageLoading.set(false);
    }
  }

  async cancelOmr(): Promise<void> {
    const jobId = this.omrJobId;
    if (!jobId || this.cancellingOmr()) return;
    try {
      const response = await this.fetch('/api/omr/cancel', {
        method: 'POST',
        headers: { 'X-Arpeon-Session': this.token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ jobId }),
        signal: AbortSignal.timeout(10_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      const result: unknown = await response.json();
      if (
        !response.ok ||
        typeof result !== 'object' ||
        result === null ||
        !('cancelled' in result) ||
        result.cancelled !== true
      )
        throw new Error(
          'Não foi possível cancelar este reconhecimento. Aguarde a conclusão ou tente novamente.',
        );
      if (this.disposed || this.omrJobId !== jobId) return;
      this.cancellingOmr.set(true);
      this.omrPending.set(null);
      this.clearOmrOriginal();
      this.omrMessage.set(
        this.recognizingOmr()
          ? 'Cancelamento solicitado. Aguarde a liberação do reconhecimento.'
          : 'Reconhecimento cancelado. A partitura atual foi preservada.',
      );
      this.error.set('');
    } catch (failure) {
      if (!this.disposed)
        this.error.set(failure instanceof Error ? failure.message : 'Falha ao cancelar.');
    }
  }

  async acceptOmr(): Promise<void> {
    const preview = this.omrPending();
    if (!preview || !this.omrAcknowledged || this.busy()) return;
    if (
      this.session.projectDirty() &&
      !window.confirm('A partitura atual tem alterações não salvas. Quer substituí-la?')
    )
      return;
    this.busy.set(true);
    this.error.set('');
    try {
      if (await this.session.acceptOmr(preview.token)) {
        this.omrJobId = null;
        this.omrPending.set(null);
        this.clearOmrOriginal();
        this.imported.emit();
      } else this.error.set(this.session.error());
    } finally {
      this.busy.set(false);
    }
  }

  async accept(): Promise<void> {
    const preview = this.pending();
    if (!preview || !this.acknowledged || this.busy()) return;
    if (
      this.session.projectDirty() &&
      !window.confirm('A partitura atual tem alterações não salvas. Quer substituí-la?')
    )
      return;
    this.busy.set(true);
    this.error.set('');
    try {
      if (
        await this.session.acceptImport(preview.token, this.fallbackOctave, this.equalNoteTicks)
      ) {
        this.pending.set(null);
        this.imported.emit();
      } else this.error.set(this.session.error());
    } finally {
      this.busy.set(false);
    }
  }

  async previewDraftNotation(): Promise<void> {
    const preview = this.pending();
    if (!preview || this.drawingDraft()) return;
    const generation = ++this.draftNotationGeneration;
    this.drawingDraft.set(true);
    this.draftXml.set('');
    this.draftNotationError.set('');
    try {
      const response = await this.fetch('/api/import/notation', {
        method: 'POST',
        headers: { 'X-Arpeon-Session': this.token, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          token: preview.token,
          fallbackOctave: this.fallbackOctave,
          equalNoteTicks: this.equalNoteTicks,
        }),
        signal: AbortSignal.timeout(30_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      const result: unknown = await response.json();
      if (
        !response.ok ||
        !result ||
        typeof result !== 'object' ||
        !('xml' in result) ||
        typeof result.xml !== 'string'
      )
        throw new Error(
          'Não foi possível desenhar o rascunho. Confira as escolhas e tente novamente.',
        );
      if (
        !this.disposed &&
        generation === this.draftNotationGeneration &&
        preview.token === this.pending()?.token
      )
        this.draftXml.set(result.xml);
    } catch (failure) {
      if (!this.disposed && generation === this.draftNotationGeneration)
        this.draftNotationError.set(
          failure instanceof Error ? failure.message : 'Falha ao desenhar rascunho.',
        );
    } finally {
      if (generation === this.draftNotationGeneration) this.drawingDraft.set(false);
    }
  }

  resetDraftNotation(): void {
    ++this.draftNotationGeneration;
    this.draftXml.set('');
    this.draftNotationError.set('');
    this.drawingDraft.set(false);
  }

  private async analyze(
    bytes: ArrayBuffer | Uint8Array,
    filename: string,
    kind: string,
  ): Promise<void> {
    this.resetDraftNotation();
    this.pending.set(null);
    this.omrPending.set(null);
    this.clearOmrOriginal();
    this.acknowledged = false;
    this.draftPage.set(0);
    if (!bytes.byteLength) {
      this.error.set('A fonte está vazia.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      if (!this.token) throw new Error('Abra esta janela pelo aplicativo ARPEON.');
      const response = await this.fetch('/api/import/preview', {
        method: 'POST',
        headers: {
          'X-Arpeon-Session': this.token,
          'X-Arpeon-Filename': encodeURIComponent(filename),
          'X-Arpeon-Import-Kind': kind,
          'Content-Type': 'application/octet-stream',
        },
        body: bytes instanceof Uint8Array ? Uint8Array.from(bytes).buffer : bytes,
        signal: AbortSignal.timeout(180_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      const result: unknown = await response.json();
      if (!response.ok) {
        if (
          typeof result === 'object' &&
          result !== null &&
          'error' in result &&
          typeof result.error === 'string'
        )
          throw new Error(result.error);
        throw new Error('Não foi possível analisar esta fonte.');
      }
      if (!isPreview(result)) throw new Error('Prévia inválida.');
      this.pending.set(result);
    } catch (failure: unknown) {
      this.error.set(failure instanceof Error ? failure.message : 'Falha de importação.');
    } finally {
      this.busy.set(false);
    }
  }

  noteLabel(note: DraftNote): string {
    const accidental =
      { SHARP: '#', FLAT: 'b', DOUBLE_SHARP: '##', DOUBLE_FLAT: 'bb' }[
        note.accidental as 'SHARP' | 'FLAT' | 'DOUBLE_SHARP' | 'DOUBLE_FLAT'
      ] ?? '';
    return note.step + accidental + (note.octave ?? '?');
  }

  ngOnDestroy(): void {
    this.resetDraftNotation();
    if (this.omrJobId) void this.cancelOmr();
    this.disposed = true;
    if (this.omrStatusTimer !== undefined) window.clearTimeout(this.omrStatusTimer);
    this.clearOmrOriginal();
  }

  private scheduleOmrStatusPoll(): void {
    this.omrStatusTimer = window.setTimeout(async () => {
      if (this.disposed || !this.recognizingOmr()) return;
      await this.refreshOmrStatus();
      if (this.recognizingOmr()) this.scheduleOmrStatusPoll();
    }, 700);
  }

  private clearOmrOriginal(): void {
    this.omrPageGeneration++;
    this.omrPdfFile = null;
    this.omrPageLoading.set(false);
    this.omrPageError.set('');
    this.omrOriginalPage.set(1);
    this.clearOmrPageImage();
    const url = this.omrOriginalUrl();
    if (url) URL.revokeObjectURL(url);
    this.omrOriginalUrl.set('');
  }

  private clearOmrPageImage(): void {
    const url = this.omrPageImage();
    if (url) URL.revokeObjectURL(url);
    this.omrPageImage.set('');
  }
}

function isOmrStatus(value: unknown): value is OmrStatus {
  if (typeof value !== 'object' || value === null || !('available' in value)) return false;
  const status = value as Record<string, unknown>;
  return (
    typeof status['available'] === 'boolean' &&
    (!('recognizing' in status) || typeof status['recognizing'] === 'boolean') &&
    (!('recognitionPhase' in status) || typeof status['recognitionPhase'] === 'string') &&
    (!('jobId' in status) || status['jobId'] === null || typeof status['jobId'] === 'string')
  );
}

function isPreview(value: unknown): value is Preview {
  if (
    typeof value !== 'object' ||
    value === null ||
    !('token' in value) ||
    typeof value.token !== 'string' ||
    !value.token ||
    !('draft' in value) ||
    typeof value.draft !== 'object' ||
    value.draft === null
  )
    return false;
  const draft = value.draft;
  return (
    'notes' in draft &&
    Array.isArray(draft.notes) &&
    draft.notes.length > 0 &&
    draft.notes.length <= 8192 &&
    draft.notes.every(
      (note: unknown) =>
        typeof note === 'object' &&
        note !== null &&
        'step' in note &&
        typeof note.step === 'string' &&
        'accidental' in note &&
        typeof note.accidental === 'string' &&
        'octave' in note &&
        (note.octave === null || Number.isSafeInteger(note.octave)) &&
        'confidence' in note &&
        typeof note.confidence === 'number' &&
        Number.isFinite(note.confidence) &&
        note.confidence >= 0 &&
        note.confidence <= 1,
    ) &&
    'warnings' in draft &&
    Array.isArray(draft.warnings) &&
    draft.warnings.every((warning: unknown) => typeof warning === 'string') &&
    'kind' in draft &&
    typeof draft.kind === 'string' &&
    'filename' in draft &&
    typeof draft.filename === 'string' &&
    'sha256' in draft &&
    typeof draft.sha256 === 'string' &&
    /^[a-f0-9]{64}$/.test(draft.sha256) &&
    'rhythm' in draft &&
    typeof draft.rhythm === 'string' &&
    (!('source' in value) ||
      value.source === null ||
      (typeof value.source === 'object' &&
        value.source !== null &&
        'providerId' in value.source &&
        typeof value.source.providerId === 'string' &&
        'pageUrl' in value.source &&
        typeof value.source.pageUrl === 'string' &&
        'fetchedAt' in value.source &&
        typeof value.source.fetchedAt === 'string' &&
        'licenseStatus' in value.source &&
        typeof value.source.licenseStatus === 'string' &&
        'licenseShortName' in value.source &&
        typeof value.source.licenseShortName === 'string' &&
        'licenseUrl' in value.source &&
        typeof value.source.licenseUrl === 'string' &&
        'notes' in value.source &&
        Array.isArray(value.source.notes)))
  );
}

function isOmrPreview(value: unknown): value is OmrPreview {
  if (typeof value !== 'object' || value === null) return false;
  const preview = value as Record<string, unknown>;
  return (
    typeof preview['token'] === 'string' &&
    preview['token'].length > 0 &&
    typeof preview['title'] === 'string' &&
    (preview['xml'] === undefined || typeof preview['xml'] === 'string') &&
    typeof preview['filename'] === 'string' &&
    typeof preview['sha256'] === 'string' &&
    /^[a-f0-9]{64}$/.test(preview['sha256']) &&
    typeof preview['pages'] === 'number' &&
    Number.isSafeInteger(preview['pages']) &&
    preview['pages'] >= 1 &&
    preview['pages'] <= 32 &&
    typeof preview['parts'] === 'number' &&
    Number.isSafeInteger(preview['parts']) &&
    preview['parts'] >= 1 &&
    preview['parts'] <= 16 &&
    typeof preview['measures'] === 'number' &&
    Number.isSafeInteger(preview['measures']) &&
    preview['measures'] >= 1 &&
    preview['measures'] <= 2048 &&
    Array.isArray(preview['recognizedMeasures']) &&
    preview['recognizedMeasures'].length >= 1 &&
    preview['recognizedMeasures'].length <= 2048 &&
    preview['recognizedMeasures'].every(
      (measure: unknown) =>
        typeof measure === 'object' &&
        measure !== null &&
        'part' in measure &&
        typeof measure.part === 'string' &&
        'number' in measure &&
        typeof measure.number === 'string' &&
        'events' in measure &&
        Array.isArray(measure.events) &&
        measure.events.length >= 1 &&
        measure.events.every((event: unknown) => typeof event === 'string'),
    ) &&
    Array.isArray(preview['pagesRequiringReview']) &&
    preview['pagesRequiringReview'].every(
      (page: unknown) =>
        typeof page === 'number' && Number.isSafeInteger(page) && page >= 1 && page <= 32,
    ) &&
    Array.isArray(preview['measuresRequiringReview']) &&
    preview['measuresRequiringReview'].length === preview['recognizedMeasures'].length &&
    preview['measuresRequiringReview'].every((name: unknown) => typeof name === 'string') &&
    Array.isArray(preview['warnings']) &&
    preview['warnings'].every((warning: unknown) => typeof warning === 'string') &&
    Array.isArray(preview['unsupportedSymbols']) &&
    preview['unsupportedSymbols'].every((symbol: unknown) => typeof symbol === 'string')
  );
}
