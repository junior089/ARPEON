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
  installable?: boolean;
  installing?: boolean;
  phase?: string;
  downloadedBytes?: number;
  totalBytes?: number;
}

@Component({
  selector: 'app-import-panel',
  templateUrl: './import-panel.html',
  styleUrl: './import-panel.scss',
})
export class ImportPanel implements OnInit, OnDestroy {
  readonly sourceTab = signal<'paste' | 'file' | 'url' | 'omr'>('paste');
  readonly sourceTabs = [
    { id: 'paste', label: 'Colar notas' },
    { id: 'file', label: 'MIDI / WAV' },
    { id: 'omr', label: 'PDF / imagem' },
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
  readonly installingOmr = signal(false);
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

  ngOnInit(): void {
    void this.refreshOmrStatus();
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
      if (response.ok && isOmrStatus(result)) this.omrStatus.set(result);
    } catch {
      // OMR status is optional until the user chooses its import flow.
    }
  }

  async installOmr(): Promise<void> {
    if (this.busy() || !this.omrStatus()?.installable) return;
    this.busy.set(true);
    this.installingOmr.set(true);
    this.scheduleOmrStatusPoll();
    this.error.set('');
    try {
      if (!this.token) throw new Error('Abra esta janela pelo aplicativo ARPEON.');
      const response = await this.fetch('/api/omr/install', {
        method: 'POST',
        headers: { 'X-Arpeon-Session': this.token },
        signal: AbortSignal.timeout(900_000),
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
        throw new Error('Não foi possível configurar o Audiveris.');
      }
      if (!isOmrStatus(result) || !result.available)
        throw new Error('Audiveris foi configurado, mas não pôde ser iniciado.');
      this.omrStatus.set(result);
    } catch (failure: unknown) {
      this.error.set(
        failure instanceof TypeError
          ? 'A conexão com o aplicativo local caiu. Verifique se o ARPEON continua aberto e tente novamente.'
          : failure instanceof Error
            ? failure.message
            : 'Não foi possível configurar o Audiveris.',
      );
      await this.refreshOmrStatus();
    } finally {
      this.installingOmr.set(false);
      if (this.omrStatusTimer !== undefined) window.clearTimeout(this.omrStatusTimer);
      this.busy.set(false);
    }
  }

  omrSetupLabel(status: OmrStatus): string {
    switch (status.phase) {
      case 'downloading':
        return 'Baixando o Audiveris do GitHub oficial';
      case 'verifying':
        return 'Conferindo a integridade do pacote';
      case 'extracting':
        return 'Preparando o Audiveris na pasta do usuário';
      case 'checking':
        return 'Verificando se o Audiveris inicia corretamente';
      default:
        return 'Configurando o reconhecimento óptico';
    }
  }

  omrDownloadPercent(status: OmrStatus): number {
    if (!status.totalBytes || !status.downloadedBytes) return 0;
    return Math.min(100, Math.floor((100 * status.downloadedBytes) / status.totalBytes));
  }

  omrDownloadSize(status: OmrStatus): string {
    const downloaded = ((status.downloadedBytes ?? 0) / 1_048_576).toFixed(1);
    const total = ((status.totalBytes ?? 0) / 1_048_576).toFixed(1);
    return status.totalBytes ? `${downloaded} de ${total} MiB` : `${downloaded} MiB recebidos`;
  }

  pageEnd(page: number, size: number, total: number): number {
    return Math.min((page + 1) * size, total);
  }

  textValue(event: Event): string {
    const target = event.target;
    return target instanceof HTMLInputElement ||
      target instanceof HTMLTextAreaElement ||
      target instanceof HTMLSelectElement
      ? target.value
      : '';
  }

  checked(event: Event): boolean {
    return event.target instanceof HTMLInputElement && event.target.checked;
  }

  async analyzePaste(): Promise<void> {
    const bytes = new TextEncoder().encode(this.pasted);
    await this.analyze(bytes, 'texto-colado.txt', this.notation);
  }

  async analyzeUrl(): Promise<void> {
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
    const kind = /\.midi?$/i.test(file.name) ? 'MIDI' : /\.wav$/i.test(file.name) ? 'WAV' : '';
    if (!kind) {
      this.error.set('Escolha um arquivo .mid, .midi ou .wav. MusicXML abre em Partitura.');
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
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    input.value = '';
    this.pending.set(null);
    this.omrPending.set(null);
    this.clearOmrOriginal();
    this.omrAcknowledged = false;
    this.omrPage.set(0);
    if (!/^[A-Za-z0-9._ -]+\.(pdf|png|jpe?g)$/i.test(file.name) || file.name.length > 120) {
      this.error.set('Escolha um PDF, PNG ou JPEG com nome simples.');
      return;
    }
    const max = /\.pdf$/i.test(file.name) ? 64 * 1_048_576 : 32 * 1_048_576;
    if (!file.size || file.size > max) {
      this.error.set(`Arquivo OMR fora do limite de ${max / 1_048_576} MiB.`);
      return;
    }
    this.busy.set(true);
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
          status.installable
            ? 'Audiveris não configurado. Use “Configurar Audiveris automaticamente” para preparar o reconhecimento óptico.'
            : 'Audiveris não configurado. Instale o Audiveris ou informe ARPEON_AUDIVERIS_EXECUTABLE antes de iniciar o ARPEON.',
        );
      const response = await this.fetch('/api/omr/preview', {
        method: 'POST',
        headers: {
          'X-Arpeon-Session': this.token,
          'X-Arpeon-Filename': encodeURIComponent(file.name),
          'Content-Type': 'application/octet-stream',
        },
        body: await file.arrayBuffer(),
        signal: AbortSignal.timeout(750_000),
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
        throw new Error('Falha no reconhecimento OMR.');
      }
      if (!isOmrPreview(result)) throw new Error('Prévia OMR inválida.');
      this.omrOriginalUrl.set(URL.createObjectURL(file));
      this.omrOriginalIsPdf.set(/\.pdf$/i.test(file.name));
      this.omrPending.set(result);
    } catch (failure: unknown) {
      this.error.set(
        failure instanceof TypeError
          ? 'A conexão com o aplicativo local caiu. Verifique se o ARPEON continua aberto e tente novamente.'
          : failure instanceof Error
            ? failure.message
            : 'Falha no reconhecimento OMR.',
      );
    } finally {
      this.busy.set(false);
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

  private async analyze(
    bytes: ArrayBuffer | Uint8Array,
    filename: string,
    kind: string,
  ): Promise<void> {
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
    if (this.omrStatusTimer !== undefined) window.clearTimeout(this.omrStatusTimer);
    this.clearOmrOriginal();
  }

  private scheduleOmrStatusPoll(): void {
    this.omrStatusTimer = window.setTimeout(async () => {
      if (!this.installingOmr()) return;
      await this.refreshOmrStatus();
      if (this.installingOmr()) this.scheduleOmrStatusPoll();
    }, 700);
  }

  private clearOmrOriginal(): void {
    const url = this.omrOriginalUrl();
    if (url) URL.revokeObjectURL(url);
    this.omrOriginalUrl.set('');
  }
}

function isOmrStatus(value: unknown): value is OmrStatus {
  if (typeof value !== 'object' || value === null || !('available' in value)) return false;
  const status = value as Record<string, unknown>;
  return (
    typeof status['available'] === 'boolean' &&
    (!('installable' in status) || typeof status['installable'] === 'boolean') &&
    (!('installing' in status) || typeof status['installing'] === 'boolean') &&
    (!('phase' in status) || typeof status['phase'] === 'string') &&
    (!('downloadedBytes' in status) ||
      (typeof status['downloadedBytes'] === 'number' &&
        Number.isSafeInteger(status['downloadedBytes']) &&
        status['downloadedBytes'] >= 0)) &&
    (!('totalBytes' in status) ||
      (typeof status['totalBytes'] === 'number' &&
        Number.isSafeInteger(status['totalBytes']) &&
        status['totalBytes'] >= 0))
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
