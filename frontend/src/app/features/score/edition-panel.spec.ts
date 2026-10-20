import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { EditionDrafts } from '../../core/edition-drafts';
import { EditionEvidence, EditionView, editionReference, isEditionView } from './edition-contract';
import { EditionPanel } from './edition-panel';

const id = '11111111-1111-4111-8111-111111111111';
const asset = 'sha256:' + 'a'.repeat(64);
const entry = (title: string | null = 'Edição local'): EditionEvidence => ({
  id: '22222222-2222-4222-8222-222222222222',
  sourceAssetId: asset,
  edition: title
    ? { id: '33333333-3333-4333-8333-333333333333', workId: id, title, externalIdentifiers: [] }
    : null,
  citation: 'Página de rosto',
  referenceUrl: '',
  recordedAt: '2026-10-19T12:00:00Z',
});
const view = (history: EditionEvidence[] = []): EditionView => ({
  sourceAssetId: asset,
  workId: id,
  history,
  maxEntries: 16,
});
async function panel(request = vi.fn<typeof fetch>().mockResolvedValue(Response.json(view()))) {
  await TestBed.configureTestingModule({
    imports: [EditionPanel],
    providers: [
      { provide: LOCAL_FETCH, useValue: request },
      { provide: SESSION_TOKEN, useValue: 'private' },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(EditionPanel);
  fixture.componentRef.setInput('projectId', id);
  fixture.componentRef.setInput('sourceAssetId', asset);
  fixture.componentRef.setInput('workId', id);
  fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
  fixture.detectChanges();
  return {
    fixture,
    component: fixture.componentInstance,
    request,
    drafts: TestBed.inject(EditionDrafts),
    element: fixture.nativeElement as HTMLElement,
  };
}
describe('local edition declarations', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });
  it('validates source/work links, identifiers, citations and reference safety', () => {
    expect(isEditionView(view([entry()]), asset, id)).toBe(true);
    expect(isEditionView(view([entry(null)]), asset, id)).toBe(true);
    expect(isEditionView(view([entry(), entry()]), asset, id)).toBe(false);
    expect(isEditionView(view([{ ...entry(), citation: '' }]), asset, id)).toBe(false);
    expect(isEditionView(view([{ ...entry(), sourceAssetId: 'other' }]), asset, id)).toBe(false);
    expect(isEditionView(view([{ ...entry(), recordedAt: 'invalid' }]), asset, id)).toBe(false);
    expect(
      isEditionView(
        view([{ ...entry(), referenceUrl: 'https://user:secret@example.org/file' }]),
        asset,
        id,
      ),
    ).toBe(false);
    for (const reference of [
      'http://example.org/',
      'https://example.org:443/',
      'https://example.org/?',
      'https://example.org/#',
      'file:///secret',
      'https://example.org/?x=1',
      'https://example.org/#x',
    ])
      expect(editionReference(reference)).toBe(false);
  });
  it('sends a revision guard and clears the draft only after exact acknowledgment', async () => {
    const { fixture, component, request, drafts, element } = await panel();
    component.begin('identify');
    component.change('title', 'Edição local');
    component.change('citation', 'Página de rosto');
    expect(drafts.pending()).toBe(true);
    request.mockResolvedValueOnce(Response.json(view([entry()])));
    await component.save(new Event('submit'));
    fixture.detectChanges();
    expect(request).toHaveBeenLastCalledWith(
      `/api/projects/${id}/editions`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          sourceAssetId: asset,
          expectedRevision: 0,
          title: 'Edição local',
          citation: 'Página de rosto',
          referenceUrl: '',
        }),
        headers: expect.objectContaining({ 'X-Arpeon-Session': 'private' }),
      }),
    );
    expect(drafts.pending()).toBe(false);
    expect(component.mode()).toBeNull();
    expect(element.textContent).toContain('Identificação registrada');
    expect(element.textContent).toContain('Declaração local');
  });
  it('keeps input after write failure, invalid acknowledgment and history reload', async () => {
    const { component, request, drafts } = await panel();
    component.begin('identify');
    component.change('title', 'Edição local');
    component.change('citation', 'Página de rosto');
    request.mockResolvedValueOnce(Response.json({ error: 'Disco indisponível' }, { status: 500 }));
    await component.save(new Event('submit'));
    expect(component.error()).toContain('Disco indisponível');
    expect(drafts.pending()).toBe(true);
    request.mockResolvedValueOnce(Response.json(view([entry('Outra edição')])));
    await component.save(new Event('submit'));
    expect(component.error()).toContain('não foi confirmado');
    expect(component.title()).toBe('Edição local');
    request.mockResolvedValueOnce(Response.json(view([entry()])));
    component.reload();
    await vi.waitFor(() => expect(component.loading()).toBe(false));
    expect(component.citation()).toBe('Página de rosto');
    expect(drafts.pending()).toBe(true);
    component.cancel();
    expect(drafts.pending()).toBe(false);
  });
  it('preserves the exact prior history when appending withdrawal', async () => {
    const { component, request } = await panel(
      vi.fn<typeof fetch>().mockResolvedValue(Response.json(view([entry()]))),
    );
    component.begin('withdraw');
    component.change('citation', 'Referência incompatível');
    const withdrawal = {
      ...entry(null),
      id: '44444444-4444-4444-8444-444444444444',
      citation: 'Referência incompatível',
    };
    request.mockResolvedValueOnce(Response.json(view([entry(), withdrawal])));
    await component.save(new Event('submit'));
    expect(component.latest()?.edition).toBeNull();
    expect(component.view()?.history).toEqual([entry(), withdrawal]);
    expect(component.message()).toContain('retirada');
  });
  it('restores a pending draft when the library panel is reopened and aborts old requests', async () => {
    const { fixture, component, drafts, request } = await panel();
    component.begin('identify');
    component.change('title', 'Rascunho');
    component.change('citation', 'Chapa 123');
    const signal = request.mock.calls[0]?.[1]?.signal;
    fixture.destroy();
    expect(signal?.aborted).toBe(true);
    expect(drafts.pending()).toBe(true);
    const reopened = TestBed.createComponent(EditionPanel);
    for (const [name, value] of Object.entries({ projectId: id, sourceAssetId: asset, workId: id }))
      reopened.componentRef.setInput(name, value);
    reopened.detectChanges();
    await vi.waitFor(() => expect(reopened.componentInstance.loading()).toBe(false));
    expect(reopened.componentInstance.title()).toBe('Rascunho');
    expect(reopened.componentInstance.citation()).toBe('Chapa 123');
    reopened.componentInstance.cancel();
    expect(drafts.pending()).toBe(false);
  });
});
