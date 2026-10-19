import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import {
  isMeasureEventsPage,
  isAlignmentPreview,
  AlignmentPreview,
  SoundBankInfo,
  isSoundBankList,
  isPartImportReview,
  PartImportReview,
  RehearsalView,
  isTransport,
  isWorkspace,
  PreviewView,
  ScoreView,
  TransportState,
  WorkspaceView,
} from '../../core/score-contract';
import { isStudyLocation, StudyLocation } from '../analysis/analysis-contract';
import type { StudyRequest } from '../analysis/study-report';

export interface ProjectInfo {
  id: string;
  title: string;
  filename: string;
  updatedAt: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isProjectId(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value);
}

function isProjectInfo(value: unknown): value is ProjectInfo {
  return (
    isRecord(value) &&
    typeof value['id'] === 'string' &&
    isProjectId(value['id']) &&
    typeof value['title'] === 'string' &&
    typeof value['filename'] === 'string' &&
    typeof value['updatedAt'] === 'string' &&
    Number.isFinite(Date.parse(value['updatedAt']))
  );
}

function isSavedProject(value: unknown): value is { id: string; updatedAt: string } {
  return (
    isRecord(value) &&
    typeof value['id'] === 'string' &&
    isProjectId(value['id']) &&
    typeof value['updatedAt'] === 'string' &&
    Number.isFinite(Date.parse(value['updatedAt']))
  );
}

function isOpenedProject(value: unknown): value is { id: string; view: WorkspaceView } {
  return (
    isRecord(value) &&
    typeof value['id'] === 'string' &&
    isProjectId(value['id']) &&
    isWorkspace(value['view'])
  );
}

function isDesktopSession(value: unknown): value is { scoreOpen: boolean; projectId: string } {
  return (
    isRecord(value) &&
    typeof value['scoreOpen'] === 'boolean' &&
    typeof value['projectId'] === 'string' &&
    (value['projectId'] === '' || isProjectId(value['projectId']))
  );
}

@Injectable()
export class ScoreSession implements OnDestroy {
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly score = signal<ScoreView | null>(null);
  readonly transport = signal<TransportState | null>(null);
  readonly xml = signal('');
  readonly preview = signal<PreviewView | null>(null);
  readonly rehearsal = signal<RehearsalView>({ viewMode: 'full', hiddenParts: [] });
  private readonly preferencesDirty = signal(false);
  readonly busy = signal(false);
  readonly opening = signal(false);
  readonly savingProject = signal(false);
  readonly projectSaveError = signal('');
  readonly error = signal('');
  readonly projects = signal<ProjectInfo[]>([]);
  readonly projectId = signal<string | null>(null);
  readonly savedRevision = signal<number | null>(null);
  readonly studySelection = signal<StudyLocation | null>(null);
  readonly projectDirty = computed(
    () =>
      !!this.score() &&
      (this.preferencesDirty() ||
        this.projectId() === null ||
        this.savedRevision() !== this.score()?.revision),
  );
  private timer: ReturnType<typeof setTimeout> | undefined;
  private projectSaveTimer: ReturnType<typeof setTimeout> | undefined;
  private openController: AbortController | undefined;
  private generation = 0;
  private disposed = false;
  readonly soundBanks = signal<SoundBankInfo[]>([]);

  async refreshSoundBanks(): Promise<void> {
    try {
      const result = await this.request('/api/soundbanks');
      if (!isSoundBankList(result)) throw new Error('Lista de bancos de sons inválida.');
      this.soundBanks.set(result);
    } catch (failure: unknown) {
      this.showError(failure);
    }
  }

  async importSoundBank(file: File): Promise<void> {
    if (this.busy() || this.mixerUpdating()) return;
    if (!/\.(sf2|dls)$/i.test(file.name) || file.size > 128 * 1024 * 1024) {
      this.error.set('Escolha um banco SF2 ou DLS de até 128 MiB.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request('/api/soundbanks', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/octet-stream',
          'X-Arpeon-Filename': encodeURIComponent(file.name),
        },
        body: file,
      });
      if (!isTransport(result)) throw new Error('Resposta do banco de sons inválida.');
      if (this.disposed) return;
      this.transport.set(result);
      this.preferencesDirty.set(true);
      await this.refreshSoundBanks();
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
      this.scheduleProjectSave();
      if (
        !this.disposed &&
        generation === this.generation &&
        this.transport()?.status === 'PLAYING'
      )
        this.schedulePoll(generation);
    }
  }

  async deleteSoundBank(id: string): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const result = await this.request('/api/soundbanks/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id }),
      });
      if (!isSoundBankList(result)) throw new Error('Lista de bancos de sons inválida.');
      this.soundBanks.set(result);
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
    }
  }
  readonly mixerUpdating = signal(false);
  readonly mixerDraft = signal<Record<string, number>>({});
  private readonly mixerQueue = new Map<
    string,
    { name: 'volume' | 'pan' | 'master'; value: number; id?: string }
  >();
  private mixerTimer: ReturnType<typeof setTimeout> | undefined;

  /** Coalesce pointer/keyboard input while preserving the final value of every control. */
  queueLevel(name: 'volume' | 'pan' | 'master', value: number, id?: string): void {
    if (!this.score() || this.disposed || !Number.isFinite(value)) return;
    const key = `${name}:${id ?? ''}`;
    this.mixerQueue.set(key, { name, value, id });
    this.mixerDraft.update((draft) => ({ ...draft, [key]: value }));
    if (!this.mixerUpdating()) {
      this.mixerUpdating.set(true);
      const score = this.score();
      this.mixerTimer = setTimeout(() => void this.flushMixer(score), 40);
    }
  }

  private async flushMixer(score: ScoreView | null): Promise<void> {
    if (this.disposed || this.score() !== score) {
      this.mixerQueue.clear();
      this.mixerDraft.set({});
      this.mixerUpdating.set(false);
      return;
    }
    if (this.busy()) {
      this.mixerTimer = setTimeout(() => void this.flushMixer(score), 40);
      return;
    }
    const entry = this.mixerQueue.entries().next().value;
    if (!entry) {
      this.mixerUpdating.set(false);
      return;
    }
    const [key, command] = entry;
    this.mixerQueue.delete(key);
    await this.action(
      command.name,
      command.id ? { id: command.id, value: command.value } : { value: command.value },
    );
    if (!this.mixerQueue.has(key))
      this.mixerDraft.update((draft) => {
        const next = { ...draft };
        delete next[key];
        return next;
      });
    this.mixerTimer = setTimeout(() => void this.flushMixer(score), 40);
  }
  private readonly loadedMeasureEvents = new Set<string>();
  private readonly measureEventRequests = new Map<string, Promise<boolean>>();

  async reconnect(): Promise<boolean> {
    const generation = ++this.generation;
    clearTimeout(this.timer);
    this.busy.set(true);
    this.error.set('');
    try {
      const state = await this.request('/api/session');
      if (!isDesktopSession(state)) throw new Error('Estado da sessão local inválido.');
      if (this.disposed || generation !== this.generation) return false;
      if (!state.scoreOpen) return false;
      const result = await this.request('/api/score');
      if (!isWorkspace(result)) throw new Error('Resposta da partitura inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.installScore(result.score);
      this.transport.set(result.transport);
      this.xml.set(result.xml);
      this.preview.set(result.preview);
      this.rehearsal.set(result.rehearsal ?? { viewMode: 'full', hiddenParts: [] });
      this.projectId.set(state.projectId || null);
      this.savedRevision.set(state.projectId ? result.score.revision : null);
      this.projectSaveError.set('');
      if (result.transport.status === 'PLAYING') this.schedulePoll(generation);
      return true;
    } catch (failure: unknown) {
      if (!this.disposed && generation === this.generation) this.showError(failure);
      return false;
    } finally {
      if (generation === this.generation) this.busy.set(false);
    }
  }

  async acceptImport(
    token: string,
    fallbackOctave: number,
    equalNoteTicks: number,
  ): Promise<boolean> {
    if (this.busy()) return false;
    this.cancelProjectSave();
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request('/api/import/accept', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, fallbackOctave, equalNoteTicks, acknowledge: true }),
      });
      if (!isWorkspace(result)) throw new Error('Resposta da partitura inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.installScore(result.score);
      this.transport.set(result.transport);
      this.xml.set(result.xml);
      this.preview.set(result.preview);
      this.rehearsal.set(result.rehearsal ?? { viewMode: 'full', hiddenParts: [] });
      this.projectId.set(null);
      this.preferencesDirty.set(false);
      this.savedRevision.set(null);
      this.projectSaveError.set('');
      return true;
    } catch (failure: unknown) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
      this.scheduleProjectSave();
    }
  }

  async acceptOmr(token: string): Promise<boolean> {
    if (this.busy()) return false;
    this.cancelProjectSave();
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request('/api/omr/accept', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, acknowledge: true }),
      });
      if (!isWorkspace(result)) throw new Error('Resposta do OMR inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.installScore(result.score);
      this.transport.set(result.transport);
      this.xml.set(result.xml);
      this.preview.set(result.preview);
      this.rehearsal.set(result.rehearsal ?? { viewMode: 'full', hiddenParts: [] });
      this.projectId.set(null);
      this.preferencesDirty.set(false);
      this.savedRevision.set(null);
      this.projectSaveError.set('');
      return true;
    } catch (failure: unknown) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
      this.scheduleProjectSave();
    }
  }

  async open(file: File): Promise<boolean> {
    if (this.busy()) return false;
    const compressed = /\.(mxl|mscz)$/i.test(file.name);
    if (!/\.(musicxml|xml|mxl|mscz)$/i.test(file.name) || file.size > 128 * 1_048_576) {
      this.error.set('Selecione um MusicXML, MXL ou MuseScore de até 128 MiB.');
      return false;
    }
    this.cancelProjectSave();
    this.busy.set(true);
    this.opening.set(true);
    this.error.set('');
    const generation = ++this.generation;
    const controller = new AbortController();
    this.openController = controller;
    clearTimeout(this.timer);
    try {
      if (!compressed) {
        const decoder = new TextDecoder('utf-8', { fatal: true });
        try {
          if (typeof file.stream === 'function') {
            const reader = file.stream().getReader();
            const cancelRead = () => void reader.cancel();
            controller.signal.addEventListener('abort', cancelRead, { once: true });
            try {
              while (true) {
                if (controller.signal.aborted) throw new Error('Importação cancelada.');
                const chunk = await reader.read();
                if (chunk.done) break;
                decoder.decode(chunk.value, { stream: true });
              }
            } finally {
              controller.signal.removeEventListener('abort', cancelRead);
              reader.releaseLock();
            }
          } else {
            const chunkSize = 1024 * 1024;
            for (let offset = 0; offset < file.size; offset += chunkSize) {
              if (controller.signal.aborted) throw new Error('Importação cancelada.');
              const chunk = await file
                .slice(offset, Math.min(file.size, offset + chunkSize))
                .arrayBuffer();
              decoder.decode(chunk, { stream: true });
            }
          }
          decoder.decode();
        } catch {
          if (controller.signal.aborted) throw new Error('Importação cancelada.');
          throw new Error('Esta versão requer MusicXML codificado em UTF-8.');
        }
      }
      const result = await this.request(compressed ? '/api/score/open-mxl' : '/api/score/open', {
        method: 'POST',
        headers: {
          'Content-Type': compressed ? 'application/octet-stream' : 'application/xml',
          'X-Arpeon-Filename': encodeURIComponent(file.name),
        },
        body: file,
        signal: controller.signal,
      });
      if (!isWorkspace(result)) throw new Error('Resposta da partitura inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.installScore(result.score);
      this.transport.set(result.transport);
      this.xml.set(result.xml);
      this.preview.set(result.preview);
      this.rehearsal.set(result.rehearsal ?? { viewMode: 'full', hiddenParts: [] });
      this.projectId.set(null);
      this.preferencesDirty.set(false);
      this.savedRevision.set(null);
      this.projectSaveError.set('');
      return true;
    } catch (failure: unknown) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
      this.opening.set(false);
      this.scheduleProjectSave();
      if (this.openController === controller) this.openController = undefined;
      if (
        !this.disposed &&
        generation === this.generation &&
        this.transport()?.status === 'PLAYING'
      )
        this.schedulePoll(generation);
    }
  }

  cancelOpen(): void {
    this.openController?.abort();
  }

  async stageParts(file: File): Promise<PartImportReview | null> {
    if (!this.score() || this.busy()) return null;
    if (!/\.(musicxml|xml|mxl|mscz)$/i.test(file.name) || file.size > 128 * 1024 * 1024) {
      this.error.set('Use MusicXML ou MXL de até 128 MiB.');
      return null;
    }
    this.busy.set(true);
    this.error.set('');
    try {
      const compressed = /\.(mxl|mscz)$/i.test(file.name);
      const result = await this.request('/api/score/parts/import', {
        method: 'POST',
        headers: {
          'Content-Type': compressed ? 'application/octet-stream' : 'application/xml',
          'X-Arpeon-Filename': encodeURIComponent(file.name),
        },
        body: file,
      });
      if (!isPartImportReview(result))
        throw new Error('Resposta da identificação de partes inválida.');
      return result;
    } catch (failure: unknown) {
      this.showError(failure);
      return null;
    } finally {
      this.busy.set(false);
      this.scheduleProjectSave();
    }
  }

  async previewAlignment(params: Record<string, string | boolean>): Promise<AlignmentPreview> {
    const result = await this.request('/api/score/parts/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params),
    });
    if (!isAlignmentPreview(result)) throw new Error('Resposta do alinhamento inválida.');
    return result;
  }

  async edit(
    name: string,
    params: Record<string, string | number | boolean> = {},
  ): Promise<boolean> {
    if (!this.score() || this.busy()) return false;
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request(`/api/score/edit/${name}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params),
      });
      if (!isWorkspace(result)) throw new Error('Resposta da edição inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.installScore(result.score);
      if (['view-mode', 'part-visibility'].includes(name)) this.preferencesDirty.set(true);
      this.transport.set(result.transport);
      this.xml.set(result.xml);
      this.preview.set(result.preview);
      this.rehearsal.set(result.rehearsal ?? { viewMode: 'full', hiddenParts: [] });
      return true;
    } catch (failure: unknown) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
      this.scheduleProjectSave();
      if (
        !this.disposed &&
        generation === this.generation &&
        this.transport()?.status === 'PLAYING'
      )
        this.schedulePoll(generation);
    }
  }

  async export(partId?: string): Promise<void> {
    if (!this.score() || this.busy() || !this.token) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const response = await this.fetch(
        '/api/score/export' + (partId ? '?partId=' + encodeURIComponent(partId) : ''),
        {
          headers: { 'X-Arpeon-Session': this.token },
          signal: AbortSignal.timeout(60_000),
          cache: 'no-store',
          credentials: 'omit',
        },
      );
      if (!response.ok) throw new Error('Não foi possível exportar o MusicXML.');
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = partId ? 'arpeon-parte.musicxml' : 'arpeon-editado.musicxml';
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
    }
  }

  async refreshProjects(): Promise<void> {
    try {
      const result = await this.request('/api/projects');
      if (!Array.isArray(result) || !result.every(isProjectInfo))
        throw new Error('Lista de projetos inválida.');
      this.projects.set(result);
    } catch (failure: unknown) {
      this.showError(failure);
    }
  }

  async saveProject(autosave = false, asNew = false): Promise<boolean> {
    if (!this.score() || this.busy()) return false;
    this.cancelProjectSave();
    this.busy.set(true);
    this.savingProject.set(true);
    this.error.set('');
    try {
      const id = asNew ? null : this.projectId();
      const result = await this.request('/api/projects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...(id ? { id } : {}), ...(autosave ? { autosave: true } : {}) }),
      });
      if (!isSavedProject(result)) throw new Error('Resposta ao salvar projeto inválida.');
      this.projectId.set(result.id);
      this.savedRevision.set(this.score()?.revision ?? null);
      this.preferencesDirty.set(false);
      this.projectSaveError.set('');
      await this.refreshProjects();
      return true;
    } catch (failure: unknown) {
      const detail =
        failure instanceof Error && failure.message.startsWith('Sessão expirada')
          ? failure.message
          : 'Não foi possível salvar o projeto. Suas alterações continuam abertas. Tente novamente ou exporte uma cópia.';
      this.projectSaveError.set(detail);
      this.error.set(detail);
      return false;
    } finally {
      this.busy.set(false);
      this.savingProject.set(false);
    }
  }

  async openProject(id: string): Promise<boolean> {
    if (this.busy() || !isProjectId(id)) return false;
    this.cancelProjectSave();
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request(`/api/projects/${id}/open`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      });
      if (!isOpenedProject(result) || result.id !== id)
        throw new Error('Resposta ao abrir projeto inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.installScore(result.view.score);
      this.transport.set(result.view.transport);
      this.xml.set(result.view.xml);
      this.preview.set(null);
      this.rehearsal.set(result.view.rehearsal ?? { viewMode: 'full', hiddenParts: [] });
      this.projectId.set(id);
      this.savedRevision.set(result.view.score.revision);
      this.preferencesDirty.set(false);
      this.projectSaveError.set('');
      return true;
    } catch (failure: unknown) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
      this.scheduleProjectSave();
    }
  }

  async manageProject(
    id: string,
    operation: 'rename' | 'duplicate',
    name: string,
  ): Promise<string | null> {
    if (this.busy() || !isProjectId(id)) return null;
    this.busy.set(true);
    this.error.set('');
    try {
      const result = await this.request(`/api/projects/${id}/${operation}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name.trim() }),
      });
      if (!isSavedProject(result) || (operation === 'rename' && result.id !== id))
        throw new Error('Resposta da biblioteca inválida.');
      await this.refreshProjects();
      return result.id;
    } catch (failure: unknown) {
      this.showError(failure);
      return null;
    } finally {
      this.busy.set(false);
      this.scheduleProjectSave();
    }
  }

  async deleteProject(id: string): Promise<boolean> {
    if (this.busy() || !isProjectId(id)) return false;
    this.busy.set(true);
    this.error.set('');
    try {
      const result = await this.request(`/api/projects/${id}`, { method: 'DELETE' });
      if (!isRecord(result) || result['deleted'] !== id)
        throw new Error('Resposta ao excluir projeto inválida.');
      if (this.projectId() === id) {
        this.projectId.set(null);
        this.preferencesDirty.set(false);
        this.savedRevision.set(null);
      }
      this.projects.update((projects) => projects.filter((project) => project.id !== id));
      return true;
    } catch (failure: unknown) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
    }
  }

  async loadMeasureEvents(partId: string, measureIndex: number): Promise<boolean> {
    const current = this.score();
    const part = current?.parts.find((candidate) => candidate.id === partId);
    const measure = part?.measures[measureIndex];
    if (!current || !measure || measure.events.length > 0) return !!measure;
    const key = `${current.revision}:${partId}:${measureIndex}`;
    if (this.loadedMeasureEvents.has(key)) return true;
    const pending = this.measureEventRequests.get(key);
    if (pending) return pending;
    const request = (async () => {
      try {
        const params = new URLSearchParams({ partId, measureIndex: String(measureIndex) });
        const page = await this.request(`/api/score/measure-events?${params.toString()}`);
        if (
          !isMeasureEventsPage(page) ||
          page.revision !== current.revision ||
          page.partId !== partId ||
          page.index !== measureIndex ||
          page.number !== measure.number
        )
          throw new Error('Resposta dos eventos do compasso inválida.');
        if (this.score()?.revision !== current.revision) return false;
        this.score.update((score) => {
          if (!score || score.revision !== current.revision) return score;
          return {
            ...score,
            parts: score.parts.map((scorePart) =>
              scorePart.id !== partId
                ? scorePart
                : {
                    ...scorePart,
                    measures: scorePart.measures.map((scoreMeasure) =>
                      scoreMeasure.index === measureIndex
                        ? {
                            ...scoreMeasure,
                            events: page.events,
                            durationNumerator: page.durationNumerator,
                            durationDenominator: page.durationDenominator,
                          }
                        : scoreMeasure,
                    ),
                  },
            ),
          };
        });
        this.loadedMeasureEvents.add(key);
        return true;
      } catch (failure: unknown) {
        this.showError(failure);
        return false;
      } finally {
        this.measureEventRequests.delete(key);
      }
    })();
    this.measureEventRequests.set(key, request);
    return request;
  }

  async action(
    name: string,
    params: Record<string, string | number | boolean> = {},
  ): Promise<void> {
    if (!this.score() || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request(`/api/transport/${name}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params),
      });
      if (!isTransport(result)) throw new Error('Resposta do transporte inválida.');
      if (this.disposed || generation !== this.generation) return;
      this.transport.set(result);
      if (!['play', 'pause', 'stop', 'seek', 'retry-sounds'].includes(name))
        this.preferencesDirty.set(true);
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
      this.scheduleProjectSave();
      if (
        !this.disposed &&
        generation === this.generation &&
        this.transport()?.status === 'PLAYING'
      )
        this.schedulePoll(generation);
    }
  }

  async prepareStudy(
    request: StudyRequest & {
      sha256: string;
      revision: number;
      partId: string;
      soundingSemitones: number;
      scoreXmlSha256: string;
    },
  ): Promise<boolean> {
    const current = this.score();
    if (!current || this.busy()) return false;
    if (current.sha256 !== request.sha256 || current.revision !== request.revision) {
      this.error.set('A partitura mudou. Analise esta revisão antes de preparar o trecho.');
      return false;
    }
    this.busy.set(true);
    this.error.set('');
    const generation = ++this.generation;
    clearTimeout(this.timer);
    try {
      const result = await this.request('/api/score/study', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      });
      if (
        !isRecord(result) ||
        !isTransport(result['transport']) ||
        !isStudyLocation(result['location']) ||
        result['transport'].status === 'PLAYING' ||
        result['transport'].activePartId !== request.partId ||
        result['location'].index !== request.expectedIndex
      )
        throw new Error('Resposta de preparação do estudo inválida.');
      if (this.disposed || generation !== this.generation) return false;
      this.transport.set(result['transport']);
      this.studySelection.set(result['location']);
      return true;
    } catch (failure) {
      this.showError(failure);
      return false;
    } finally {
      this.busy.set(false);
      if (
        !this.disposed &&
        generation === this.generation &&
        this.transport()?.status === 'PLAYING'
      )
        this.schedulePoll(generation);
    }
  }

  private schedulePoll(generation: number): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.poll(generation), 80);
  }

  private installScore(score: ScoreView): void {
    this.studySelection.set(null);
    this.score.set(score);
    this.loadedMeasureEvents.clear();
    this.measureEventRequests.clear();
    for (const part of score.parts)
      for (const measure of part.measures)
        if (measure.events.length > 0)
          this.loadedMeasureEvents.add(`${score.revision}:${part.id}:${measure.index}`);
  }

  private scheduleProjectSave(): void {
    if (!this.projectDirty()) return;
    this.cancelProjectSave();
    this.projectSaveTimer = setTimeout(() => {
      this.projectSaveTimer = undefined;
      void this.saveProject(true);
    }, 900);
  }

  private cancelProjectSave(): void {
    clearTimeout(this.projectSaveTimer);
    this.projectSaveTimer = undefined;
  }

  private async poll(generation: number): Promise<void> {
    try {
      const result = await this.request('/api/transport');
      if (this.disposed || generation !== this.generation) return;
      if (!isTransport(result)) throw new Error('Posição de reprodução inválida.');
      const previous = this.transport();
      if (
        previous &&
        (previous.startingBpm !== result.startingBpm ||
          previous.tempoPractice?.enabled !== result.tempoPractice?.enabled)
      ) {
        this.preferencesDirty.set(true);
        this.scheduleProjectSave();
      }
      this.transport.set(result);
      if (result.status === 'PLAYING') this.schedulePoll(generation);
    } catch (failure: unknown) {
      if (this.disposed || generation !== this.generation) return;
      this.showError(failure);
      this.transport.set(null);
      // Java stops playback after the poll lease expires.
    }
  }

  async refreshTransport(): Promise<void> {
    if (!this.score() || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    const generation = this.generation;
    try {
      const result = await this.request('/api/transport');
      if (this.disposed || generation !== this.generation) return;
      if (!isTransport(result)) throw new Error('Posição de reprodução inválida.');
      this.transport.set(result);
      if (result.status === 'PLAYING') this.schedulePoll(generation);
    } catch (failure: unknown) {
      this.showError(failure);
    } finally {
      this.busy.set(false);
    }
  }

  private async request(path: string, options: RequestInit = {}): Promise<unknown> {
    if (!this.token) throw new Error('Abra esta janela pelo aplicativo ARPEON.');
    let response: Response;
    try {
      response = await this.fetch(path, {
        ...options,
        headers: { 'X-Arpeon-Session': this.token, ...options.headers },
        signal:
          options.signal ??
          AbortSignal.timeout(
            path === '/api/score/open' || path === '/api/score/open-mxl' ? 300_000 : 30_000,
          ),
        cache: 'no-store',
        credentials: 'omit',
      });
    } catch {
      if (options.signal?.aborted) throw new Error('Importação cancelada.');
      throw new Error(
        'A conexão local foi interrompida. Verifique se o ARPEON está aberto e tente novamente.',
      );
    }
    const result: unknown = await response.json();
    if (!response.ok) {
      if (response.status === 401 || response.status === 403)
        throw new Error('Sessão expirada. Abra o ARPEON novamente.');
      if (
        typeof result === 'object' &&
        result !== null &&
        'error' in result &&
        typeof result.error === 'string'
      )
        throw new Error(result.error);
      throw new Error('Não foi possível processar a partitura.');
    }
    return result;
  }

  private showError(failure: unknown): void {
    if (!this.disposed)
      this.error.set(failure instanceof Error ? failure.message : 'Falha inesperada.');
  }

  ngOnDestroy(): void {
    this.disposed = true;
    clearTimeout(this.mixerTimer);
    this.mixerQueue.clear();
    this.openController?.abort();
    ++this.generation;
    clearTimeout(this.timer);
    this.cancelProjectSave();
    if (this.transport()?.status === 'PLAYING')
      void this.request('/api/transport/stop', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      }).catch(() => {
        /* The Java poll lease also stops playback. */
      });
  }
}
