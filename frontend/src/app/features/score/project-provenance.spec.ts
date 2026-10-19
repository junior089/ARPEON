import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { isProjectDetails, ProjectDetails, ProjectProvenancePanel } from './project-provenance';

const projectId = '11111111-1111-4111-8111-111111111111';
function details(): ProjectDetails {
  return {
    xmlSha256: 'b'.repeat(64),
    provenance: {
      schemaVersion: 1,
      work: { id: projectId, title: 'Estudo', composer: '', externalIdentifiers: [] },
      arrangement: {
        id: '22222222-2222-4222-8222-222222222222',
        workId: projectId,
        parts: [{ id: 'P1', name: 'Flauta', instrumentId: 'flute' }],
      },
      sourceAsset: {
        id: 'sha256:' + 'a'.repeat(64),
        filename: 'estudo.musicxml',
        sha256: 'a'.repeat(64),
        format: 'MUSICXML',
        byteSize: 4,
        retained: true,
        externalIdentifiers: [],
      },
      rights: { status: 'NOT_REVIEWED', derivation: 'UNKNOWN', redistribution: 'UNKNOWN' },
    },
  };
}
async function panel(request = vi.fn<typeof fetch>().mockResolvedValue(Response.json(details()))) {
  await TestBed.configureTestingModule({
    imports: [ProjectProvenancePanel],
    providers: [
      { provide: LOCAL_FETCH, useValue: request },
      { provide: SESSION_TOKEN, useValue: 'test-session' },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProjectProvenancePanel);
  fixture.componentRef.setInput('projectId', projectId);
  fixture.detectChanges();
  await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
  fixture.detectChanges();
  return {
    fixture,
    component: fixture.componentInstance,
    request,
    element: fixture.nativeElement as HTMLElement,
  };
}
describe('saved project provenance', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });
  it('validates identity links, retained file size, rights and safe names', () => {
    const data = details();
    expect(isProjectDetails(data)).toBe(true);
    expect(isProjectDetails({ xmlSha256: 'a'.repeat(64) })).toBe(true);
    const p = data.provenance;
    if (!p) throw new Error('Expected fixture provenance');
    expect(
      isProjectDetails({
        ...data,
        provenance: { ...p, arrangement: { ...p.arrangement, workId: 'other' } },
      }),
    ).toBe(false);
    expect(
      isProjectDetails({
        ...data,
        provenance: { ...p, sourceAsset: { ...p.sourceAsset, filename: '../outside.xml' } },
      }),
    ).toBe(false);
    expect(
      isProjectDetails({
        ...data,
        provenance: { ...p, sourceAsset: { ...p.sourceAsset, byteSize: 0 } },
      }),
    ).toBe(false);
    expect(
      isProjectDetails({
        ...data,
        provenance: { ...p, rights: { ...p.rights, redistribution: 'ALLOWED' } },
      }),
    ).toBe(false);
    expect(isProjectDetails({ ...data, xmlSha256: 'not-a-hash' })).toBe(false);
  });
  it('shows actual evidence without inferring an edition, composer or publication permission', async () => {
    const { element, request } = await panel();
    expect(element.textContent).toContain('Não informado');
    expect(element.textContent).toContain('Não identificada');
    expect(element.textContent).toContain('Direitos não avaliados');
    expect(element.textContent).toContain('SHA-256 da entrada');
    expect(element.querySelector('button')?.textContent).toContain('Baixar original');
    expect(request.mock.calls[0]?.[1]?.headers).toMatchObject({
      'X-Arpeon-Session': 'test-session',
    });
  });
  it('shows legacy state and never offers a reconstructed original', async () => {
    const { element } = await panel(
      vi.fn<typeof fetch>().mockResolvedValue(Response.json({ xmlSha256: 'b'.repeat(64) })),
    );
    expect(element.textContent).toContain('Projeto legado');
    expect(element.textContent).toContain('não é possível reconstruir');
    expect(element.querySelector('button')).toBeNull();
  });
  it('keeps failure visible and lets a retry recover authoritative metadata', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({ error: 'missing' }, { status: 500 }));
    const { component, fixture, element } = await panel(request);
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Não foi possível');
    request.mockResolvedValueOnce(Response.json(details()));
    component.reload();
    fixture.detectChanges();
    await vi.waitFor(() => expect(component.provenance()?.sourceAsset.retained).toBe(true));
    fixture.detectChanges();
    expect(component.provenance()?.sourceAsset.retained).toBe(true);
    expect(element.querySelector('[role="alert"]')).toBeNull();
  });
  it('rejects a mismatched original response before triggering a download', async () => {
    const { component, request } = await panel();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click');
    request.mockResolvedValueOnce(new Response('wrong length'));
    await component.download();
    expect(component.error()).toContain('tamanho divergente');
    expect(component.downloading()).toBe(false);
    expect(click).not.toHaveBeenCalled();
  });
  it('cancels stale requests when another project is selected', async () => {
    let firstSignal: AbortSignal | null | undefined;
    const request = vi
      .fn<typeof fetch>()
      .mockImplementationOnce((_path, init) => {
        firstSignal = init?.signal;
        return new Promise<Response>((resolve) => {
          firstSignal?.addEventListener('abort', () => resolve(Response.json(details())));
        });
      })
      .mockResolvedValueOnce(Response.json({ xmlSha256: 'c'.repeat(64) }));
    await TestBed.configureTestingModule({
      imports: [ProjectProvenancePanel],
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'test-session' },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectProvenancePanel);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.detectChanges();
    fixture.componentRef.setInput('projectId', '33333333-3333-4333-8333-333333333333');
    fixture.detectChanges();
    await vi.waitFor(() =>
      expect(fixture.componentInstance.details()?.xmlSha256).toBe('c'.repeat(64)),
    );
    fixture.detectChanges();
    expect(firstSignal?.aborted).toBe(true);
    expect(fixture.componentInstance.details()?.xmlSha256).toBe('c'.repeat(64));
  });
});
