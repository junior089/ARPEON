import { Component, EventEmitter, inject, Output, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { ScoreSession } from '../score/score-session';

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
  draft: {
    kind: string;
    filename: string;
    sha256: string;
    rhythm: string;
    notes: DraftNote[];
    warnings: string[];
  };
}

@Component({
  selector: 'app-import-panel',
  templateUrl: './import-panel.html',
  styleUrl: './import-panel.scss',
})
export class ImportPanel {
  @Output() readonly imported = new EventEmitter<void>();
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly session = inject(ScoreSession);
  readonly pending = signal<Preview | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  notation: 'NOTES' | 'SOLFEGE' | 'CIFRA_MELODICA' = 'CIFRA_MELODICA';
  pasted = '';
  fallbackOctave = 4;
  equalNoteTicks = 480;
  acknowledged = false;

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
    const max = kind === 'WAV' ? 524_288 : 1_048_576;
    if (!file.size || file.size > max) {
      this.error.set(`Arquivo fora do limite de ${kind === 'WAV' ? '512 KiB' : '1 MiB'}.`);
      return;
    }
    await this.analyze(await file.arrayBuffer(), file.name, kind);
  }

  async accept(): Promise<void> {
    const preview = this.pending();
    if (!preview || !this.acknowledged || this.busy()) return;
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
    this.acknowledged = false;
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
    draft.notes.length <= 512 &&
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
    typeof draft.rhythm === 'string'
  );
}
