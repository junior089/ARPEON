import { Component, computed, inject, input, output, signal } from '@angular/core';
import { DesktopPreferences } from '../../core/desktop-preferences';
import { ScoreReadingPreferences } from '../../core/score-reading-preferences';
import { Appearance } from '../../shared/appearance';
import { WorkspaceLayout } from '../../shared/workspace-layout';
import { AudioPreparation } from '../../shared/audio-preparation';

type Category = 'interface' | 'score' | 'audio' | 'updates';
const categories: { id: Category; label: string; keywords: string }[] = [
  {
    id: 'interface',
    label: 'Interface e acessibilidade',
    keywords:
      'tema da interface claro escuro aparência painéis explorador de partes inspetor de propriedades zoom da partitura layout restaurar movimento reduzido teclado',
  },
  {
    id: 'score',
    label: 'Partituras e estudo',
    keywords:
      'editor papel escuro na partitura mostrar nomes das notas leitura português letras exibir oitava oitavas seleção atual todas as notas acompanhar digitação de flauta instrumento restaurar',
  },
  {
    id: 'audio',
    label: 'Áudio e dispositivos',
    keywords:
      'microfone entrada entradas dispositivos afinação referência lá hz afinador prática análise preparar áudio atualizar ouvir guardar ajustes',
  },
  {
    id: 'updates',
    label: 'Atualizações',
    keywords:
      'versão instalação manutenção verificar verificações automático automáticas consentimento gerenciar atualizações',
  },
];

function normalized(value: string): string {
  return value.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('pt-BR');
}

@Component({
  selector: 'app-settings-panel',
  imports: [AudioPreparation],
  templateUrl: './settings-panel.html',
  styleUrl: './settings-panel.scss',
})
export class SettingsPanel {
  readonly appearance = inject(Appearance);
  readonly layout = inject(WorkspaceLayout);
  readonly reading = inject(ScoreReadingPreferences);
  readonly preferences = inject(DesktopPreferences);
  readonly captureActive = input(false);
  readonly busy = input(false);
  readonly version = input('');
  readonly manageUpdates = output<void>();
  readonly query = signal('');
  readonly category = signal<Category>('interface');
  readonly restored = signal('');
  readonly results = computed(() => {
    const terms = normalized(this.query()).split(/\s+/u).filter(Boolean);
    return categories.filter((item) =>
      terms.every((term) => normalized(item.label + ' ' + item.keywords).includes(term)),
    );
  });
  readonly visible = computed(() =>
    this.query().trim() ? this.results() : categories.filter((item) => item.id === this.category()),
  );

  select(category: Category): void {
    this.category.set(category);
    this.query.set('');
    this.restored.set('');
  }

  reset(category: 'interface' | 'score'): void {
    if (category === 'interface') {
      this.appearance.setTheme('dark');
      this.layout.reset();
    } else {
      this.reading.reset();
      if (this.layout.scoreDark()) this.layout.toggleScoreTheme();
    }
    this.restored.set('Padrões restaurados. Os ajustes serão guardados neste computador.');
  }
}
