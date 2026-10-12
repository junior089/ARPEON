import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, output, signal } from '@angular/core';
import { ProjectInfo, ScoreSession } from './score-session';

const searchable = (value: string) =>
  value
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLocaleLowerCase('pt-BR');

@Component({
  selector: 'app-project-library',
  imports: [DatePipe, NgTemplateOutlet],
  templateUrl: './project-library.html',
  styleUrl: './project-library.scss',
})
export class ProjectLibrary {
  readonly session = inject(ScoreSession);
  readonly opened = output<string>();
  readonly query = signal('');
  readonly order = signal('recent');
  readonly page = signal(0);
  readonly selectedId = signal('');
  readonly operation = signal<'rename' | 'duplicate' | null>(null);
  readonly name = signal('');
  readonly message = signal('');
  readonly selected = computed(() =>
    this.session.projects().find((p) => p.id === this.selectedId()),
  );
  readonly filtered = computed(() => {
    const query = searchable(this.query().trim());
    return this.session
      .projects()
      .filter((p) => searchable(`${p.title} ${p.filename}`).includes(query))
      .sort((a, b) =>
        this.order() === 'name'
          ? (a.title || a.filename).localeCompare(b.title || b.filename, 'pt-BR')
          : b.updatedAt.localeCompare(a.updatedAt),
      );
  });
  readonly pageCount = computed(() => Math.max(1, Math.ceil(this.filtered().length / 8)));
  readonly currentPage = computed(() => Math.min(this.page(), this.pageCount() - 1));
  readonly visible = computed(() =>
    this.filtered().slice(this.currentPage() * 8, (this.currentPage() + 1) * 8),
  );
  readonly validName = computed(
    () =>
      this.name().trim().length > 0 &&
      this.name().trim().length <= 200 &&
      !Array.from(this.name()).some((character) => {
        const code = character.charCodeAt(0);
        return code < 32 || (code >= 127 && code <= 159);
      }),
  );

  filter(event: Event, order = false): void {
    const value =
      event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement
        ? event.target.value
        : '';
    if (order) this.order.set(value);
    else this.query.set(value);
    this.page.set(0);
    this.selectedId.set('');
    this.operation.set(null);
    this.message.set('');
  }

  select(project: ProjectInfo): void {
    this.selectedId.set(this.selectedId() === project.id ? '' : project.id);
    this.operation.set(null);
    this.message.set('');
  }

  prepare(operation: 'rename' | 'duplicate'): void {
    const project = this.selected();
    if (!project) return;
    this.operation.set(operation);
    const name = project.title || project.filename;
    this.name.set(operation === 'duplicate' ? `${name.slice(0, 192)} — cópia` : name);
    this.message.set('');
  }

  async submit(event: Event): Promise<void> {
    event.preventDefault();
    const project = this.selected();
    const operation = this.operation();
    if (!project || !operation || !this.validName()) return;
    const id = await this.session.manageProject(project.id, operation, this.name());
    if (!id) return;
    this.selectedId.set(id);
    this.operation.set(null);
    this.message.set(
      operation === 'duplicate'
        ? 'Cópia criada. A partitura aberta continua a mesma.'
        : 'Nome do projeto atualizado.',
    );
    this.query.set('');
    this.order.set('recent');
    this.page.set(0);
  }

  async remove(): Promise<void> {
    const project = this.selected();
    if (
      !project ||
      !window.confirm(
        `Excluir o projeto “${project.title || project.filename}” deste computador? A partitura aberta permanece disponível; o histórico de análise deste projeto também será excluído.`,
      )
    )
      return;
    if (await this.session.deleteProject(project.id)) {
      this.selectedId.set('');
      this.operation.set(null);
      this.message.set('Projeto excluído da biblioteca.');
    }
  }
}
