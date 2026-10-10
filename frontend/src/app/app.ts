import { Component, ElementRef, HostListener, inject, signal, ViewChild } from '@angular/core';
import { Icon } from './shared/icon';
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

@Component({
  selector: 'app-root',
  imports: [
    Icon,
    TunerPanel,
    ScoreWorkspace,
    TransportBar,
    PracticePanel,
    ImportPanel,
    AnalysisPanel,
  ],
  providers: [ScoreSession],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  readonly score = inject(ScoreSession);
  readonly view = signal<'score' | 'tuner' | 'practice' | 'import' | 'analysis'>('score');
  readonly shuttingDown = signal(false);
  readonly shutdownError = signal('');
  readonly layout = inject(WorkspaceLayout);
  readonly appearance = inject(Appearance);
  readonly modifier = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl';
  readonly areas = [
    { id: 'score', label: 'Partitura' },
    { id: 'import', label: 'Importar' },
    { id: 'practice', label: 'Prática' },
    { id: 'tuner', label: 'Afinador' },
    { id: 'analysis', label: 'Análise' },
  ] as const;
  readonly menus: {
    label: string;
    items: { label: string; command: string; shortcut?: string }[];
  }[] = [
    {
      label: 'Arquivo',
      items: [
        { label: 'Abrir partitura…', command: 'open', shortcut: this.modifier + ' O' },
        { label: 'Salvar projeto', command: 'save', shortcut: this.modifier + ' S' },
        { label: 'Importar outras fontes…', command: 'import' },
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
        { label: 'Tema escuro', command: 'theme-dark' },
        { label: 'Tema claro', command: 'theme-light' },
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
    { label: 'Ajuda', items: [{ label: 'Ajuda e atalhos', command: 'help', shortcut: 'F1' }] },
  ];
  @ViewChild(ScoreWorkspace) private editor?: ScoreWorkspace;
  @ViewChild('help') private help?: ElementRef<HTMLDialogElement>;
  @ViewChild('globalFile') private globalFile?: ElementRef<HTMLInputElement>;
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);

  navigate(view: 'score' | 'tuner' | 'practice' | 'import' | 'analysis'): void {
    this.view.set(view);
    this.closeMenus();
  }

  available(command: string): boolean {
    if (['save', 'export'].includes(command)) return !!this.score.score() && !this.score.busy();
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
        this.layout.inspector.set(true);
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
    if (this.editor) {
      await this.editor.open(event);
      return;
    }
    const input = event.target;
    if (!(input instanceof HTMLInputElement) || !input.files?.[0]) return;
    const file = input.files[0];
    input.value = '';
    if (
      this.score.projectDirty() &&
      !window.confirm('A partitura atual tem alterações não salvas. Quer substituí-la?')
    )
      return;
    if (await this.score.open(file)) this.navigate('score');
  }

  private closeMenus(except?: HTMLDetailsElement): void {
    this.element.nativeElement
      .querySelectorAll<HTMLDetailsElement>('.menu[open]')
      .forEach((menu) => {
        if (menu !== except) menu.open = false;
      });
  }

  @HostListener('document:click', ['$event'])
  outsideMenu(event: MouseEvent): void {
    if (event.target instanceof Element && !event.target.closest('.menubar')) this.closeMenus();
  }

  menuKeys(event: KeyboardEvent): void {
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
      this.closeMenus();
      if (next) {
        next.open = true;
        next.querySelector('summary')?.focus();
      }
    }
  }

  @HostListener('document:keydown', ['$event'])
  shortcuts(event: KeyboardEvent): void {
    if (event.repeat || this.help?.nativeElement.open) return;
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
      else if (key === 's') command = 'save';
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

  async shutdown(): Promise<void> {
    const unsaved = this.score.projectDirty();
    const detail = unsaved ? '\n\nHá alterações que ainda não foram salvas como projeto.' : '';
    if (!window.confirm(`Encerrar o ARPEON e parar o servidor local?${detail}`)) return;
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
