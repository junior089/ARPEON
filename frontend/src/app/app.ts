import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  output,
  signal,
  ViewChild,
} from '@angular/core';
import { Icon, IconName } from './shared/icon';
import { WorkspaceLayout } from './shared/workspace-layout';
import { Appearance } from './shared/appearance';
import { TunerPanel } from './features/tuner/tuner-panel';
import { ScoreWorkspace } from './features/score/score-workspace';
import { ScoreSession } from './features/score/score-session';
import { TransportBar } from './features/transport/transport-bar';
import { PracticePanel } from './features/practice/practice-panel';
import { ImportPanel } from './features/import/import-panel';
import { AnalysisPanel } from './features/analysis/analysis-panel';
import { LOCAL_FETCH, SESSION_TOKEN } from './core/local-session';
import { DesktopPreferences } from './core/desktop-preferences';
import { ImportRouting, importRoute, SCORE_FILE_ACCEPT } from './core/import-routing';
import { ProjectLibrary } from './features/score/project-library';
import { SettingsPanel } from './features/settings/settings-panel';
import { AudioSettings } from './core/audio-settings';
import { ReferenceTone } from './core/reference-tone';
import { StudyProfiles } from './core/study-profiles';
import { ScoreReadingPreferences } from './core/score-reading-preferences';

@Component({
  selector: 'app-workstation',
  imports: [
    Icon,
    TunerPanel,
    ScoreWorkspace,
    TransportBar,
    PracticePanel,
    ImportPanel,
    AnalysisPanel,
    ProjectLibrary,
    SettingsPanel,
  ],
  providers: [ScoreSession],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  readonly profiles = inject(StudyProfiles);
  private readonly readingPreferences = inject(ScoreReadingPreferences);
  readonly profileRequested = output<void>();
  readonly profileChanging = signal(false);
  readonly profileChangeError = signal('');

  async requestProfiles(): Promise<void> {
    if (this.profileChanging() || !this.profiles.account()) return;
    this.profileChangeError.set('');
    if (
      this.score.busy() ||
      this.score.savingProject() ||
      this.captureActive() ||
      this.importer?.busy() ||
      this.importer?.recognizingOmr() ||
      this.importer?.pending() ||
      this.importer?.omrPending()
    ) {
      this.profileChangeError.set(
        'Conclua ou cancele a operação, a captura ou o rascunho de importação antes de trocar de perfil.',
      );
      return;
    }
    this.profileChanging.set(true);
    try {
      if (this.score.score()) {
        await this.score.action('stop');
        if (this.score.error()) throw new Error(this.score.error());
        if (this.score.projectDirty() && !(await this.score.saveProject())) return;
      }
      this.tone.stop();
      await this.readingPreferences.flush();
      await this.audio.flush();
      if (this.preferences.error() || this.audio.preferencesError())
        throw new Error('Guarde os ajustes pendentes antes de trocar de perfil.');
      this.productDialog?.nativeElement.close();
      this.profileRequested.emit();
    } catch (failure) {
      this.profileChangeError.set(
        failure instanceof Error ? failure.message : 'Não foi possível preparar a troca de perfil.',
      );
    } finally {
      this.profileChanging.set(false);
    }
  }
  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopUpdateStatusPolling());
  }

  readonly audio = inject(AudioSettings);
  readonly tone = inject(ReferenceTone);
  readonly productPage = signal<'settings' | 'updates' | 'changes' | 'about'>('about');
  readonly updateVersion = signal('');
  readonly updateEnabled = signal<boolean | null>(null);
  readonly updateStatusMessage = signal('');
  readonly automaticUpdates = signal(false);
  readonly savingUpdatePreference = signal(false);
  readonly updateChannel = signal('');
  readonly updateState = signal('idle');
  readonly updatePreferenceError = signal('');
  private updateCheckStartedAt = 0;
  readonly updateLastChecked = signal('');
  private updateStatusTimer?: ReturnType<typeof setInterval>;
  readonly loadingUpdateStatus = signal(false);
  readonly releaseSections = [
    {
      title: 'Recursos',
      items: [
        'Edição, revisões e exportação MusicXML.',
        'MIDI, partes, metrônomo, contagem e A–B.',
        'Reconhecimento local de PDF e imagens.',
        'Afinador, prática de flauta e análise monofônica.',
      ],
    },
    {
      title: 'Melhorias desta revisão',
      items: [
        'Metrônomo e contagem no player; BPM exato e ajustes de 1 ou 5 BPM.',
        'Guia de flauta com mapa das chaves, mãos e diagramas por nota.',
        'Avisos de extensão com acesso à nota, voz e cabeça de acorde.',
        'Seletores e ações nos temas Ouro e Carvão.',
      ],
    },
    {
      title: 'Limites',
      items: [
        'MusicXML exporta sem a camada de leitura.',
        'OMR exige revisão; sem confiança ou coordenadas por nota.',
        'Atualizador nativo, sujeito à configuração de distribuição.',
      ],
    },
  ];
  readonly fileAccept = SCORE_FILE_ACCEPT;
  private readonly routing = inject(ImportRouting);
  readonly dragging = signal(false);
  readonly fileError = signal('');
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly score = inject(ScoreSession);
  readonly view = signal<'score' | 'tuner' | 'practice' | 'import' | 'analysis' | 'library'>(
    'score',
  );
  readonly shuttingDown = signal(false);
  readonly shutdownError = signal('');
  readonly updateMessage = signal('');
  readonly checkingUpdates = signal(false);
  readonly layout = inject(WorkspaceLayout);
  readonly appearance = inject(Appearance);
  readonly preferences = inject(DesktopPreferences);
  readonly menusOpen = signal(false);
  readonly modifier = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl';
  readonly areas = [
    { id: 'score', label: 'Partitura' },
    { id: 'import', label: 'Importar' },
    { id: 'practice', label: 'Prática' },
    { id: 'tuner', label: 'Afinador' },
    { id: 'analysis', label: 'Análise' },
  ] as const;
  readonly sidebar: { id: string; label: string; icon: IconName; command: string }[] = [
    { id: 'library', label: 'Biblioteca', icon: 'folder', command: 'library' },
    { id: 'score', label: 'Partituras', icon: 'score', command: 'score' },
    { id: 'play', label: 'Reprodução', icon: 'play', command: 'mode-play' },
    { id: 'edit', label: 'Edição', icon: 'edit', command: 'mode-edit' },
    { id: 'tuner', label: 'Ferramentas', icon: 'tuner', command: 'tuner' },
    { id: 'practice', label: 'Aprendizado', icon: 'study', command: 'practice' },
    { id: 'analysis', label: 'Análise', icon: 'analysis', command: 'analysis' },
  ];
  sidebarSelected(id: string): boolean {
    if (this.view() !== 'score') return this.view() === id;
    if (id === 'play' || id === 'edit') return this.layout.mode() === id;
    return id === 'score' && !['play', 'edit'].includes(this.layout.mode());
  }
  async showLibrary(): Promise<void> {
    this.navigate('library');
    await this.score.refreshProjects();
  }
  async openProject(id: string): Promise<void> {
    if (
      this.score.projectDirty() &&
      !window.confirm('A partitura atual tem alterações não salvas. Quer substituí-la?')
    )
      return;
    if (await this.score.openProject(id)) this.navigate('score');
  }
  readonly menus: {
    label: string;
    items: { label: string; command: string; shortcut?: string }[];
  }[] = [
    {
      label: 'Arquivo',
      items: [
        { label: 'Abrir ou importar arquivo…', command: 'open', shortcut: this.modifier + ' O' },
        { label: 'Salvar projeto', command: 'save', shortcut: this.modifier + ' S' },
        { label: 'Salvar como novo projeto', command: 'save-as', shortcut: this.modifier + ' ⇧ S' },
        { label: 'Importar texto ou fonte online…', command: 'import' },
        { label: 'Exportar MusicXML', command: 'export' },
        { label: 'Encerrar ARPEON', command: 'shutdown' },
      ],
    },
    {
      label: 'Editar',
      items: [
        { label: 'Desfazer', command: 'undo', shortcut: this.modifier + ' Z' },
        { label: 'Refazer', command: 'redo', shortcut: this.modifier + ' ⇧ Z' },
      ],
    },
    {
      label: 'Exibir',
      items: [
        {
          label: 'Mostrar / ocultar explorador',
          command: 'explorer',
          shortcut: this.modifier + ' B',
        },
        {
          label: 'Mostrar / ocultar inspetor',
          command: 'inspector',
          shortcut: this.modifier + ' Alt B',
        },
        { label: 'Restaurar layout', command: 'reset' },
        { label: 'Modo Tocar', command: 'mode-play' },
        { label: 'Modo Editar', command: 'mode-edit' },
        { label: 'Modo Estudar', command: 'mode-study' },
        { label: 'Tema escuro', command: 'theme-dark' },
        { label: 'Tema claro', command: 'theme-light' },
        { label: 'Preferências…', command: 'settings' },
      ],
    },
    {
      label: 'Partitura',
      items: [
        { label: 'Editor de partituras', command: 'score' },
        { label: 'Propriedades e transposição', command: 'properties' },
      ],
    },
    {
      label: 'Reprodução',
      items: [
        { label: 'Reproduzir / pausar', command: 'play', shortcut: 'Espaço' },
        { label: 'Parar', command: 'stop', shortcut: '⇧ Espaço' },
        { label: 'Ativar / desativar metrônomo', command: 'metronome' },
      ],
    },
    {
      label: 'Ferramentas',
      items: [
        { label: 'Prática de flauta', command: 'practice' },
        { label: 'Afinador cromático', command: 'tuner' },
        { label: 'Análise de execução', command: 'analysis' },
      ],
    },
    {
      label: 'Ajuda',
      items: [
        { label: 'Ajuda e atalhos', command: 'help', shortcut: 'F1' },
        { label: 'Verificar atualizações…', command: 'updates' },
        { label: 'Notas da versão', command: 'changes' },
        { label: 'Sobre o ARPEON', command: 'about' },
      ],
    },
  ];
  @ViewChild(ScoreWorkspace) private editor?: ScoreWorkspace;
  @ViewChild('help') private help?: ElementRef<HTMLDialogElement>;
  @ViewChild('globalFile') private globalFile?: ElementRef<HTMLInputElement>;
  @ViewChild('productDialog') private productDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild(TunerPanel) private tunerPanel?: TunerPanel;
  @ViewChild(PracticePanel) private practicePanel?: PracticePanel;
  @ViewChild(AnalysisPanel) private analysisPanel?: AnalysisPanel;
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);

  navigate(view: 'score' | 'tuner' | 'practice' | 'import' | 'analysis' | 'library'): void {
    this.dragging.set(false);
    this.view.set(view);
    this.closeMenus();
  }

  available(command: string): boolean {
    if (['mode-play', 'mode-edit', 'mode-study'].includes(command))
      return !!this.score.score() && !this.score.busy();
    if (['save', 'save-as', 'export'].includes(command))
      return !!this.score.score() && !this.score.busy();
    if (command === 'updates') return !this.checkingUpdates();
    if (['undo', 'redo', 'properties'].includes(command))
      return (
        this.view() === 'score' &&
        !!this.score.score() &&
        !this.score.busy() &&
        (command === 'properties' ||
          !!this.score.score()?.[command === 'undo' ? 'canUndo' : 'canRedo'])
      );
    if (['play', 'stop', 'metronome'].includes(command))
      return !!this.score.transport() && !this.score.busy();
    if (command === 'open') return !this.score.busy();
    if (command === 'shutdown') return !this.shuttingDown();
    return true;
  }

  run(command: string): void {
    if (!this.available(command)) return;
    this.closeMenus();
    switch (command) {
      case 'settings':
      case 'changes':
      case 'about':
        this.openProduct(command);
        break;
      case 'library':
        void this.showLibrary();
        break;
      case 'mode-play':
      case 'mode-edit':
      case 'mode-study': {
        const mode = command === 'mode-play' ? 'play' : command === 'mode-edit' ? 'edit' : 'study';
        if (this.editor) this.editor.setWorkspaceMode(mode);
        else this.layout.setMode(mode);
        this.navigate('score');
        break;
      }
      case 'theme-dark':
      case 'theme-light':
        this.appearance.setTheme(command === 'theme-dark' ? 'dark' : 'light');
        break;
      case 'open':
        this.globalFile?.nativeElement.click();
        break;
      case 'save':
        void this.score.saveProject();
        break;
      case 'save-as':
        void this.score.saveProject(false, true);
        break;
      case 'updates':
        void this.checkUpdates();
        break;
      case 'export':
        void this.score.export();
        break;
      case 'undo':
      case 'redo':
        void this.editor?.edit(command);
        break;
      case 'explorer':
      case 'inspector':
        this.layout.toggle(command);
        break;
      case 'reset':
        this.layout.reset();
        break;
      case 'properties':
        this.editor?.setWorkspaceMode('edit');
        break;
      case 'play':
        void this.score.action(this.score.transport()?.status === 'PLAYING' ? 'pause' : 'play');
        break;
      case 'stop':
        void this.score.action('stop');
        break;
      case 'metronome':
        void this.score.action('metronome', { enabled: !this.score.transport()?.metronome });
        break;
      case 'help':
        this.help?.nativeElement.showModal();
        break;
      case 'shutdown':
        void this.shutdown();
        break;
      case 'score':
      case 'import':
      case 'practice':
      case 'tuner':
      case 'analysis':
        this.navigate(command);
        break;
    }
  }

  menuToggled(event: Event): void {
    const target = event.target;
    if (target instanceof HTMLDetailsElement && target.open) this.closeMenus(target);
  }

  async openFile(event: Event): Promise<void> {
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    input.value = '';
    await this.routeFile(file);
  }

  async routeFile(file: File): Promise<void> {
    if (this.score.busy()) return;
    this.fileError.set('');
    const route = importRoute(file.name);
    if (route === 'unsupported') {
      this.fileError.set(
        'Formato não suportado. Use MusicXML, MXL, MSCZ de uma pauta, PDF, PNG, JPEG, MIDI ou WAV.',
      );
      return;
    }
    if (route !== 'direct') {
      if (this.view() === 'import' && this.importer) await this.importer.importFile(file);
      else {
        this.routing.pendingFile.set(file);
        this.navigate('import');
      }
      return;
    }
    if (this.editor) {
      await this.editor.openDocument(file);
      return;
    }
    if (
      this.score.projectDirty() &&
      !window.confirm('A partitura atual tem alterações não salvas. Quer substituí-la?')
    )
      return;
    if (await this.score.open(file)) this.navigate('score');
  }

  @ViewChild(ImportPanel) private importer?: ImportPanel;

  dragOver(event: DragEvent): void {
    if (!event.dataTransfer?.types.includes('Files')) return;
    event.preventDefault();
    this.dragging.set(true);
  }

  dragLeave(event: DragEvent): void {
    if (
      event.relatedTarget instanceof Node &&
      this.element.nativeElement.contains(event.relatedTarget)
    )
      return;
    this.dragging.set(false);
  }

  async dropFile(event: DragEvent): Promise<void> {
    if (event.defaultPrevented) return;
    event.preventDefault();
    this.dragging.set(false);
    const files = event.dataTransfer?.files;
    if (!files?.length) return;
    if (files.length !== 1) {
      this.fileError.set('Abra um arquivo por vez para revisar cada documento.');
      return;
    }
    const file = files[0];
    if (file) await this.routeFile(file);
  }

  private closeMenus(except?: HTMLDetailsElement): void {
    if (!except) this.menusOpen.set(false);
    this.element.nativeElement
      .querySelectorAll<HTMLDetailsElement>('.menu[open]')
      .forEach((menu) => {
        if (menu !== except) menu.open = false;
      });
  }

  @HostListener('document:click', ['$event'])
  outsideMenu(event: MouseEvent): void {
    if (event.target instanceof Element && !event.target.closest('.menubar, .menus-toggle'))
      this.closeMenus();
  }

  menuKeys(event: KeyboardEvent): void {
    if (event.key === 'Escape' && this.menusOpen()) {
      event.preventDefault();
      this.closeMenus();
      this.element.nativeElement.querySelector<HTMLButtonElement>('.menus-toggle')?.focus();
      return;
    }
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    const menu = target.closest('details');
    if (!menu) return;
    if (event.key === 'Escape') {
      menu.open = false;
      menu.querySelector('summary')?.focus();
      event.stopPropagation();
    }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      menu.open = true;
      const buttons = Array.from(menu.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'));
      const index = buttons.indexOf(target as HTMLButtonElement);
      const next =
        event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? buttons.length - 1
            : (index + (event.key === 'ArrowUp' ? -1 : 1) + buttons.length) % buttons.length;
      buttons[next]?.focus();
    }
    if (['ArrowLeft', 'ArrowRight'].includes(event.key)) {
      event.preventDefault();
      const menus = Array.from(
        this.element.nativeElement.querySelectorAll<HTMLDetailsElement>('.menu'),
      );
      const next =
        menus[
          (menus.indexOf(menu) + (event.key === 'ArrowRight' ? 1 : -1) + menus.length) %
            menus.length
        ];
      this.closeMenus(next);
      if (next) {
        next.open = true;
        next.querySelector('summary')?.focus();
      }
    }
  }

  @HostListener('document:keydown', ['$event'])
  shortcuts(event: KeyboardEvent): void {
    if (event.repeat || this.help?.nativeElement.open || this.productDialog?.nativeElement.open)
      return;
    const target = event.target;
    if (
      target instanceof HTMLElement &&
      target.closest('input, select, textarea, [contenteditable="true"]')
    )
      return;
    const key = event.key.toLowerCase();
    let command = '';
    if (event.ctrlKey || event.metaKey) {
      if (key === 'b') command = event.altKey ? 'inspector' : 'explorer';
      else if (key === 's') command = event.shiftKey ? 'save-as' : 'save';
      else if (key === 'o') command = 'open';
      else if (key === 'z') command = event.shiftKey ? 'redo' : 'undo';
      else if (key === 'y') command = 'redo';
    } else if (event.altKey && /^[1-5]$/.test(key)) command = this.areas[Number(key) - 1]?.id ?? '';
    else if (key === 'f1') command = 'help';
    else if (
      key === ' ' &&
      !(target instanceof HTMLElement && target.closest('button, a, summary'))
    )
      command = event.shiftKey ? 'stop' : 'play';
    if (command && this.available(command)) {
      event.preventDefault();
      this.run(command);
    }
  }

  @HostListener('window:arpeon-close')
  desktopClose(): void {
    void this.shutdown();
  }

  @HostListener('window:beforeunload', ['$event'])
  preventUnsavedClose(event: BeforeUnloadEvent): void {
    if (this.score.projectDirty() && !this.shuttingDown()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }

  async checkUpdates(): Promise<void> {
    if (this.checkingUpdates()) return;
    this.openProduct('updates');
    this.checkingUpdates.set(true);
    this.updateCheckStartedAt = Date.now();
    this.updateMessage.set('');
    try {
      const response = await this.fetch('/api/updates/check', {
        method: 'POST',
        headers: { 'X-Arpeon-Session': this.token, 'Content-Type': 'application/json' },
        body: '{}',
        signal: AbortSignal.timeout(10_000),
        cache: 'no-store',
        credentials: 'omit',
      });
      const result: unknown = await response.json();
      if (
        typeof result === 'object' &&
        result !== null &&
        'error' in result &&
        typeof result.error === 'string'
      )
        this.updateMessage.set(result.error);
      else if (!response.ok) throw new Error('Não foi possível verificar atualizações.');
      else this.readUpdateStatus(result);
    } catch (failure) {
      this.updateMessage.set(
        failure instanceof Error ? failure.message : 'Não foi possível verificar atualizações.',
      );
    } finally {
      this.checkingUpdates.set(false);
    }
  }

  captureActive(): boolean {
    return !!(
      this.tunerPanel?.session.active() ||
      this.practicePanel?.microphone.active() ||
      this.analysisPanel?.active()
    );
  }

  openProduct(page: 'settings' | 'updates' | 'changes' | 'about'): void {
    this.stopUpdateStatusPolling();
    this.productPage.set(page);
    if (!this.productDialog?.nativeElement.open) this.productDialog?.nativeElement.showModal();
    if (page === 'settings') void this.audio.refresh();
    void this.loadUpdateStatus();
    if (page === 'updates') {
      this.updateStatusTimer = setInterval(() => void this.loadUpdateStatus(), 2000);
    }
  }

  stopUpdateStatusPolling(): void {
    if (this.updateStatusTimer) clearInterval(this.updateStatusTimer);
    this.updateStatusTimer = undefined;
  }

  async setAutomaticUpdates(enabled: boolean): Promise<void> {
    if (this.savingUpdatePreference() || this.updateEnabled() !== true) return;
    this.savingUpdatePreference.set(true);
    this.updatePreferenceError.set('');
    try {
      const response = await this.fetch('/api/updates/preferences', {
        method: 'PUT',
        headers: { 'X-Arpeon-Session': this.token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ automaticChecking: enabled }),
        signal: AbortSignal.timeout(5000),
        cache: 'no-store',
        credentials: 'omit',
      });
      const result: unknown = await response.json();
      if (!response.ok || !this.readUpdateStatus(result))
        throw new Error('Não foi possível guardar a preferência. Tente novamente.');
    } catch (failure) {
      this.updatePreferenceError.set(
        failure instanceof Error ? failure.message : 'Falha ao guardar preferência.',
      );
    } finally {
      this.savingUpdatePreference.set(false);
    }
  }

  async loadUpdateStatus(): Promise<void> {
    if (this.loadingUpdateStatus() || this.savingUpdatePreference()) return;
    this.loadingUpdateStatus.set(true);
    try {
      const response = await this.fetch('/api/updates', {
        headers: { 'X-Arpeon-Session': this.token },
        signal: AbortSignal.timeout(5000),
        cache: 'no-store',
        credentials: 'omit',
      });
      const result: unknown = await response.json();
      if (!response.ok || !this.readUpdateStatus(result))
        throw new Error('Não foi possível consultar a versão instalada.');
    } catch (failure) {
      this.updateStatusMessage.set(
        failure instanceof Error ? failure.message : 'Falha ao consultar versão.',
      );
    } finally {
      this.loadingUpdateStatus.set(false);
    }
  }

  private readUpdateStatus(result: unknown): boolean {
    if (
      !result ||
      typeof result !== 'object' ||
      !('enabled' in result) ||
      typeof result.enabled !== 'boolean' ||
      !('version' in result) ||
      typeof result.version !== 'string' ||
      !('message' in result) ||
      typeof result.message !== 'string'
    )
      return false;
    this.updateEnabled.set(result.enabled);
    this.updateVersion.set(result.version);
    this.updateStatusMessage.set(result.message);
    this.automaticUpdates.set('automaticChecking' in result && result.automaticChecking === true);
    this.updateChannel.set(
      'channel' in result && typeof result.channel === 'string' ? result.channel : '',
    );
    this.updateState.set(
      'state' in result && typeof result.state === 'string' ? result.state : 'idle',
    );
    if (
      (this.updateState() === 'current' || this.updateState() === 'available') &&
      'lastCheckedAt' in result &&
      typeof result.lastCheckedAt === 'string' &&
      Date.parse(result.lastCheckedAt) >= this.updateCheckStartedAt
    ) {
      this.updateMessage.set('');
    }
    this.updateLastChecked.set(
      'lastCheckedAt' in result && typeof result.lastCheckedAt === 'string' && result.lastCheckedAt
        ? new Date(result.lastCheckedAt).toLocaleString('pt-BR')
        : '',
    );
    return true;
  }

  async shutdown(): Promise<void> {
    if (this.shuttingDown()) return;
    const unsaved = this.score.projectDirty();
    if (unsaved && !window.confirm('Há alterações não salvas. Encerrar sem salvar?')) return;
    await this.preferences.flush();
    this.shutdownError.set('');
    try {
      const response = await this.fetch('/api/shutdown', {
        method: 'POST',
        headers: {
          'X-Arpeon-Session': this.token,
          'Content-Type': 'application/json',
        },
        body: '{}',
        signal: AbortSignal.timeout(5000),
        cache: 'no-store',
        credentials: 'omit',
      });
      if (!response.ok) throw new Error('Não foi possível encerrar o ARPEON.');
      this.shuttingDown.set(true);
    } catch (failure) {
      this.shutdownError.set(
        failure instanceof Error ? failure.message : 'Falha ao encerrar o ARPEON.',
      );
    }
  }
}
