import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProjectLibrary } from './project-library';
import { ProjectInfo, ScoreSession } from './score-session';
import { EditionDrafts } from '../../core/edition-drafts';

const projects = (): ProjectInfo[] =>
  Array.from({ length: 10 }, (_, i) => ({
    id: `project-${i}`,
    title: i === 0 ? 'Ária de estudo' : `Estudo ${i}`,
    filename: i === 0 ? 'flute.musicxml' : `exercise-${i}.musicxml`,
    updatedAt: `2026-10-${String(i + 1).padStart(2, '0')}T12:00:00Z`,
  }));

async function library() {
  const session = {
    projects: signal(projects()),
    projectId: signal('project-0'),
    busy: signal(false),
    manageProject: vi.fn().mockResolvedValue(null),
    deleteProject: vi.fn().mockResolvedValue(false),
  };
  await TestBed.configureTestingModule({
    imports: [ProjectLibrary],
    providers: [{ provide: ScoreSession, useValue: session }],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProjectLibrary);
  fixture.detectChanges();
  return {
    fixture,
    component: fixture.componentInstance,
    root: fixture.nativeElement as HTMLElement,
    session,
  };
}

describe('ProjectLibrary', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('locates a pending declaration on another page despite an active filter', async () => {
    const { fixture, component } = await library();
    component.query.set('missing');
    TestBed.inject(EditionDrafts).requestedProject.set('project-0');
    fixture.detectChanges();
    expect(component.query()).toBe('');
    expect(component.currentPage()).toBe(1);
    expect(component.selectedId()).toBe('project-0');
    expect(component.visible().some((project) => project.id === 'project-0')).toBe(true);
    expect(TestBed.inject(EditionDrafts).requestedProject()).toBe('');
  });

  it('searches accents and filenames, sorts, paginates and opens a project directly', async () => {
    const { fixture, root, component } = await library();
    const opened = vi.fn();
    component.opened.subscribe(opened);
    expect(root.querySelectorAll('.project-open')).toHaveLength(8);
    expect(root.querySelector('.project-open')?.textContent).toContain('Estudo 9');
    root.querySelector<HTMLButtonElement>('[aria-label="Próximos projetos"]')?.click();
    fixture.detectChanges();
    expect(root.querySelectorAll('.project-open')).toHaveLength(2);
    const search = root.querySelector<HTMLInputElement>('#project-search');
    if (!search) throw new Error('Missing project search');
    search.value = 'aria';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(root.querySelectorAll('.project-open')).toHaveLength(1);
    root.querySelector<HTMLButtonElement>('.project-open')?.click();
    expect(opened).toHaveBeenCalledWith('project-0');
    search.value = 'EXERCISE-3';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(root.querySelector('.project-open')?.textContent).toContain('Estudo 3');
    search.value = 'missing';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(root.textContent).toContain('Nenhum projeto corresponde');
    search.value = '';
    search.dispatchEvent(new Event('input'));
    const order = root.querySelector<HTMLSelectElement>('[aria-label="Ordenar projetos"]');
    if (!order) throw new Error('Missing project order');
    order.value = 'name';
    order.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(root.querySelector('.project-open')?.textContent).toContain('Ária de estudo');
  });

  it('preserves the rename draft on failure and rejects empty names before submitting', async () => {
    const { fixture, root, component, session } = await library();
    const project = component.visible()[0];
    if (!project) throw new Error('Missing project');
    component.select(project);
    component.prepare('rename');
    component.name.set('   ');
    fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true);
    await component.submit(new Event('submit'));
    expect(session.manageProject).not.toHaveBeenCalled();
    component.name.set('Meu estudo');
    await component.submit(new Event('submit'));
    fixture.detectChanges();
    expect(component.name()).toBe('Meu estudo');
    expect(component.operation()).toBe('rename');
    expect(component.selected()?.id).toBe(project.id);
  });

  it('creates a named saved copy without changing the active project', async () => {
    const { fixture, component, root, session } = await library();
    const project = component.visible()[0];
    if (!project) throw new Error('Missing project');
    component.select(project);
    component.prepare('duplicate');
    fixture.detectChanges();
    expect(root.textContent).toContain('Alterações ainda não salvas não entram');
    component.name.set('Cópia lenta');
    session.manageProject.mockImplementation(async () => {
      session.projects.update((p) => [...p, { ...project, id: 'copy', title: 'Cópia lenta' }]);
      return 'copy';
    });
    await component.submit(new Event('submit'));
    fixture.detectChanges();
    expect(session.manageProject).toHaveBeenCalledWith(project.id, 'duplicate', 'Cópia lenta');
    expect(session.projectId()).toBe('project-0');
    expect(component.selectedId()).toBe('copy');
    expect(root.textContent).toContain('Cópia criada');
  });

  it('names the project in deletion confirmation and preserves it when deletion fails', async () => {
    const { component, session } = await library();
    const project = component.visible()[0];
    if (!project) throw new Error('Missing project');
    component.select(project);
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await component.remove();
    expect(confirmation.mock.calls[0]?.[0]).toContain(project.title);
    expect(session.deleteProject).not.toHaveBeenCalled();
    confirmation.mockReturnValue(true);
    await component.remove();
    expect(component.selectedId()).toBe(project.id);
    component.page.set(1);
    session.projects.set(session.projects().slice(0, 8));
    expect(component.currentPage()).toBe(0);
    expect(component.visible()).toHaveLength(8);
  });
});
